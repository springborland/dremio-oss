/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.planner.physical;

import static com.dremio.exec.planner.common.MoreRelOptUtil.identityProjects;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBiShuttle;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexPermuteInputsShuttle;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.mapping.Mapping;
import org.apache.calcite.util.mapping.Mappings;
import org.apache.calcite.util.trace.CalciteTrace;
import org.slf4j.Logger;

import com.dremio.exec.planner.logical.RelOptHelper;
import com.dremio.exec.planner.logical.RexRewriter;
import com.dremio.service.Pointer;
import com.google.common.collect.ImmutableList;

/**
 * Visits the condition associated with NLJ, and pushes down expressions to projects below the join, reducing the amount
 * of computation required in the filter evaluation
 */
public class SimplifyNLJConditionRule extends RelOptRule {
  public static final RelOptRule INSTANCE = new SimplifyNLJConditionRule();

  protected static final Logger tracer = CalciteTrace.getPlannerTracer();

  private SimplifyNLJConditionRule() {
    super(RelOptHelper.any(NestedLoopJoinPrel.class), "SimplifyNLJConditionRule");
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    NestedLoopJoinPrel nestedLoopJoin = call.rel(0);
    return !nestedLoopJoin.hasVectorExpression();
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    NestedLoopJoinPrel nlj = call.rel(0);
    RexBuilder rexBuilder = nlj.getCluster().getRexBuilder();

    RexNode condition = nlj.getCondition();

    condition = RexRewriter.rewrite(condition, ImmutableList.of(new SimplifyNLJConditionRule.TrigFractionRewriteRule(rexBuilder), new SimplifyNLJConditionRule.SinCosPlusMinusRewriteRule(rexBuilder)));

    int leftCount = nlj.getLeft().getRowType().getFieldCount();
    List<RexNode> identityProjects = identityProjects(nlj.getRowType(), nlj.getProjectedFields());
    SimplifyNLJConditionRule.ExpressionPusher pusher = new SimplifyNLJConditionRule.ExpressionPusher(rexBuilder, identityProjects, leftCount);

    Pointer<SimplifyNLJConditionRule.Side> side = new Pointer<>();

    identityProjects.forEach(p -> p.accept(pusher, side));

    side.value = SimplifyNLJConditionRule.Side.EMPTY;
    RexNode tempCondition = condition.accept(pusher, side);

    if (pusher.exprs.stream().allMatch(e -> e instanceof RexInputRef)) {
      return;
    }


    List<RexNode> leftExprs = new ArrayList<>();
    pusher.leftExprs.forEach(i -> leftExprs.add(pusher.exprs.get(i)));

    RelDataType leftType = RexUtil.createStructType(nlj.getCluster().getTypeFactory(), leftExprs,
      null, SqlValidatorUtil.F_SUGGESTER);

    ProjectPrel newLeft = ProjectPrel.create(nlj.getCluster(), nlj.getLeft().getTraitSet(), nlj.getLeft(), leftExprs, leftType);

    List<RexNode> rightExprs = new ArrayList<>();
    pusher.rightExprs.forEach(i -> rightExprs.add(RexUtil.shift(pusher.exprs.get(i), -leftCount)));

    RelDataType rightType = RexUtil.createStructType(nlj.getCluster().getTypeFactory(), rightExprs,
      null, SqlValidatorUtil.F_SUGGESTER);

    ProjectPrel newRight = ProjectPrel.create(nlj.getCluster(), nlj.getRight().getTraitSet(), nlj.getRight(), rightExprs, rightType);

    Mapping mapping = Mappings.bijection(ImmutableList.<Integer>builder().addAll(pusher.leftExprs).addAll(pusher.rightExprs).build());

    RexShuttle shuttle = new RexPermuteInputsShuttle(mapping.inverse());
    RexNode newCondition = tempCondition.accept(shuttle);

    ImmutableBitSet.Builder projectedBuilder = ImmutableBitSet.builder();
    for (RexNode r : pusher.identityProjects()) {
      RexInputRef newRef = (RexInputRef) r.accept(shuttle);
      projectedBuilder.set(newRef.getIndex());
    }

    if (newCondition.toString().equals(nlj.getCondition().toString())) {
      return;
    }

    NestedLoopJoinPrel newJoin = NestedLoopJoinPrel.create(nlj.getCluster(), nlj.getTraitSet(), newLeft, newRight, nlj.getJoinType(), newCondition, projectedBuilder.build());

    call.transformTo(newJoin);
  }

  /**
   * Categorizes whether a bit set contains bits left and right of a
   * line.
   */
  enum Side {
    LEFT, RIGHT, BOTH, EMPTY;

    static SimplifyNLJConditionRule.Side merge(List<SimplifyNLJConditionRule.Side> sides) {
      boolean hasLeft = sides.stream().anyMatch(s -> s == SimplifyNLJConditionRule.Side.LEFT);
      boolean hasRight = sides.stream().anyMatch(s -> s == SimplifyNLJConditionRule.Side.RIGHT);
      if (hasLeft && hasRight) {
        return SimplifyNLJConditionRule.Side.BOTH;
      }
      if (hasLeft) {
        return SimplifyNLJConditionRule.Side.LEFT;
      }
      if (hasRight) {
        return SimplifyNLJConditionRule.Side.RIGHT;
      }
      return SimplifyNLJConditionRule.Side.EMPTY;
    }

    static boolean shouldPushDown(List<SimplifyNLJConditionRule.Side> sides) {
      return
        sides.stream().anyMatch(s -> s == SimplifyNLJConditionRule.Side.LEFT || s == SimplifyNLJConditionRule.Side.RIGHT)
          && !sides.stream().allMatch(s -> s == SimplifyNLJConditionRule.Side.LEFT || s == SimplifyNLJConditionRule.Side.EMPTY)
          && !sides.stream().allMatch(s -> s == SimplifyNLJConditionRule.Side.RIGHT || s == SimplifyNLJConditionRule.Side.EMPTY);
    }
  }

  /**
   * Rex shuttle the rewrites the expression tree from bottom up, keeping track of what side of the join each node is on,
   * and keeping track of which expression have been pushed down, to avoid pushing down redundant expressions
   */
  private static class ExpressionPusher extends RexBiShuttle<Pointer<SimplifyNLJConditionRule.Side>> {
    private final RexBuilder rexBuilder;
    private final int leftCount;

    final List<Integer> leftExprs = new ArrayList<>();
    final List<Integer> rightExprs = new ArrayList<>();
    final List<RexNode> exprs = new ArrayList<>();
    final List<RexNode> identityProjects = new ArrayList<>();
    final Map<String,Integer> exprMap = new HashMap<>();

    ExpressionPusher(RexBuilder rexBuilder, List<RexNode> identityProjects, int leftCount) {
      this.rexBuilder = rexBuilder;
      this.leftCount = leftCount;

      for (int i = 0; i < identityProjects.size(); i++) {
        RexInputRef ref = (RexInputRef) identityProjects.get(i);
        exprs.add(ref);
        exprMap.put(ref.toString(), i);
        if (ref.getIndex() < leftCount) {
          leftExprs.add(i);
        } else {
          rightExprs.add(i);
        }
        this.identityProjects.add(new RexInputRef(i, ref.getType()));
      }
    }

    public List<RexNode> identityProjects() {
      return identityProjects;
    }

    @Override
    public RexNode visitInputRef(RexInputRef ref, Pointer<SimplifyNLJConditionRule.Side> side) {
      side.value = ref.getIndex() < leftCount ? SimplifyNLJConditionRule.Side.LEFT : SimplifyNLJConditionRule.Side.RIGHT;
      return ref;
    }

    @Override
    public RexNode visitLiteral(RexLiteral lit, Pointer<SimplifyNLJConditionRule.Side> side) {
      side.value = SimplifyNLJConditionRule.Side.EMPTY;
      return lit;
    }

    @Override
    public RexNode visitCall(RexCall call, final Pointer<SimplifyNLJConditionRule.Side> sidePointer) {
      List<RexNode> newOperands = new ArrayList<>();
      List<SimplifyNLJConditionRule.Side> sides = new ArrayList<>(); call.getOperands().forEach(o -> { Pointer<SimplifyNLJConditionRule.Side> s = new Pointer<>();
        RexNode newOperand = o.accept(this, s);
        sides.add(s.value);
        newOperands.add(newOperand);
      });
      RexNode special = handleSpecial(call, sides, sidePointer, rexBuilder);
      if (special != null) {
        return special.accept(this, sidePointer);
      }
      if (SimplifyNLJConditionRule.Side.shouldPushDown(sides)) {
        List<RexNode> finalOperands = new ArrayList<>();
        for (Pair<RexNode, SimplifyNLJConditionRule.Side> pair : Pair.zip(newOperands, sides)) {
          if (pair.right == SimplifyNLJConditionRule.Side.LEFT || pair.right == SimplifyNLJConditionRule.Side.RIGHT) {
            String digest = pair.left.toString();
            Integer index = exprMap.get(digest);
            if (index == null) {
              index = exprs.size();
              exprs.add(pair.left);
              exprMap.put(digest, index);
              if (pair.right == SimplifyNLJConditionRule.Side.LEFT) {
                leftExprs.add(index);
              } else {
                rightExprs.add(index);
              }
            }
            finalOperands.add(new RexInputRef(index, pair.left.getType()));
          } else {
            finalOperands.add(pair.left);
          }
        }
        sidePointer.value = SimplifyNLJConditionRule.Side.BOTH;
        return rexBuilder.makeCall(call.getOperator(), finalOperands);
      }
      sidePointer.value = SimplifyNLJConditionRule.Side.merge(sides);
      if (call.isA(SqlKind.CAST)) {
        return rexBuilder.makeCast(call.getType(), newOperands.get(0));
      }
      return rexBuilder.makeCall(call.getOperator(), newOperands);
    }

    private static boolean differentSides(SimplifyNLJConditionRule.Side side0, SimplifyNLJConditionRule.Side side1) {
      if (((side0 == SimplifyNLJConditionRule.Side.LEFT || side0 == SimplifyNLJConditionRule.Side.RIGHT)
        && (side1 == SimplifyNLJConditionRule.Side.LEFT || side1 == SimplifyNLJConditionRule.Side.RIGHT)
        && (side0 != side1))) {
        return true;
      }
      return false;
    }

    /**
     * Rewrites the special sin/cos sum/difference expressions using trig identities, so that they can be pushed down. If no need
     * to rewrite, restores them to standard form
     * @param call
     * @param sides
     * @param sidePointer
     * @param builder
     * @return
     */
    private RexNode handleSpecial(RexCall call, List<SimplifyNLJConditionRule.Side> sides, Pointer<SimplifyNLJConditionRule.Side> sidePointer, RexBuilder builder) {
      if (sides.size() != 2) {
        return null;
      }
      SimplifyNLJConditionRule.Side side0 = sides.get(0);
      SimplifyNLJConditionRule.Side side1 = sides.get(1);
      boolean differentSides = differentSides(side0, side1);
      RexNode op0 = call.getOperands().get(0);
      RexNode op1 = call.getOperands().get(1);
      switch (call.getOperator().getName()) {
        case "SIN_PLUS":
          if (!differentSides) {
            sidePointer.value = SimplifyNLJConditionRule.Side.merge(ImmutableList.of(side0, side1));
            return restoreTrig(call, builder);
          }
          sidePointer.value = SimplifyNLJConditionRule.Side.BOTH;
          // sin(a + b) = sin(a)cos(b) + cos(a)sin(b)
          return builder.makeCall(
            SqlStdOperatorTable.PLUS,
            builder.makeCall(
              SqlStdOperatorTable.MULTIPLY,
              builder.makeCall(SqlStdOperatorTable.SIN, op0),
              builder.makeCall(SqlStdOperatorTable.COS, op1)
            ),
            builder.makeCall(
              SqlStdOperatorTable.MULTIPLY,
              builder.makeCall(SqlStdOperatorTable.COS, op0),
              builder.makeCall(SqlStdOperatorTable.SIN, op1)
            )
          );
        case "SIN_MINUS":
          if (!differentSides) {
            sidePointer.value = SimplifyNLJConditionRule.Side.merge(ImmutableList.of(side0, side1));
            return restoreTrig(call, builder);
          }
          sidePointer.value = SimplifyNLJConditionRule.Side.BOTH;
          // sin(a - b) = sin(a)cos(b) - cos(a)sin(b)
          return builder.makeCall(
            SqlStdOperatorTable.MINUS,
            builder.makeCall(
              SqlStdOperatorTable.MULTIPLY,
              builder.makeCall(SqlStdOperatorTable.SIN, op0),
              builder.makeCall(SqlStdOperatorTable.COS, op1)
            ),
            builder.makeCall(
              SqlStdOperatorTable.MULTIPLY,
              builder.makeCall(SqlStdOperatorTable.COS, op0),
              builder.makeCall(SqlStdOperatorTable.SIN, op1)
            )
          );
        case "COS_PLUS":
          if (!differentSides) {
            sidePointer.value = SimplifyNLJConditionRule.Side.merge(ImmutableList.of(side0, side1));
            return restoreTrig(call, builder);
          }
          sidePointer.value = SimplifyNLJConditionRule.Side.BOTH;
          // cos(a + b) = cos(a)cos(b) - sin(a)sin(b)
          return builder.makeCall(
            SqlStdOperatorTable.MINUS,
            builder.makeCall(
              SqlStdOperatorTable.MULTIPLY,
              builder.makeCall(SqlStdOperatorTable.COS, op0),
              builder.makeCall(SqlStdOperatorTable.COS, op1)
            ),
            builder.makeCall(
              SqlStdOperatorTable.MULTIPLY,
              builder.makeCall(SqlStdOperatorTable.SIN, op0),
              builder.makeCall(SqlStdOperatorTable.SIN, op1)
            )
          );
        case "COS_MINUS":
          if (!differentSides) {
            sidePointer.value = SimplifyNLJConditionRule.Side.merge(ImmutableList.of(side0, side1));
            return restoreTrig(call, builder);
          }
          sidePointer.value = SimplifyNLJConditionRule.Side.BOTH;
          // cos(a - b) = cos(a)cos(b) + sin(a)sin(b)
          return builder.makeCall(
            SqlStdOperatorTable.PLUS,
            builder.makeCall(
              SqlStdOperatorTable.MULTIPLY,
              builder.makeCall(SqlStdOperatorTable.COS, op0),
              builder.makeCall(SqlStdOperatorTable.COS, op1)
            ),
            builder.makeCall(
              SqlStdOperatorTable.MULTIPLY,
              builder.makeCall(SqlStdOperatorTable.SIN, op0),
              builder.makeCall(SqlStdOperatorTable.SIN, op1)
            )
          );
        default:
          return null;
      }
    }

  }

  private static RexNode restoreTrig(RexCall call, RexBuilder builder) {
    switch(call.getOperator().getName().toUpperCase()) {
      case "SIN_PLUS":
        return builder.makeCall(
          SqlStdOperatorTable.SIN,
          builder.makeCall(
            SqlStdOperatorTable.PLUS,
            call.getOperands().get(0),
            call.getOperands().get(1)
          )
        );
      case "SIN_MINUS":
        return builder.makeCall(
          SqlStdOperatorTable.SIN,
          builder.makeCall(
            SqlStdOperatorTable.MINUS,
            call.getOperands().get(0),
            call.getOperands().get(1)
          )
        );
      case "COS_PLUS":
        return builder.makeCall(
          SqlStdOperatorTable.COS,
          builder.makeCall(
            SqlStdOperatorTable.PLUS,
            call.getOperands().get(0),
            call.getOperands().get(1)
          )
        );
      case "COS_MINUS":
        return builder.makeCall(
          SqlStdOperatorTable.COS,
          builder.makeCall(
            SqlStdOperatorTable.MINUS,
            call.getOperands().get(0),
            call.getOperands().get(1)
          )
        );
      default:
        throw new RuntimeException(String.format("Unknown special function:%s", call.getOperator().getName()));
    }
  }

  private static class TrigFractionRewriteRule extends RexRewriter.RewriteRule {

    public TrigFractionRewriteRule(RexBuilder builder) {
      super(builder);
    }

    @Override
    public RexNode rewrite(RexCall call) {
      switch(call.getOperator().getName().toUpperCase()) {
        case "SIN":
        case "COS":
        case "TAN":
          if (!(call.getOperands().get(0) instanceof RexCall)) {
            return null;
          }
          RexCall child = (RexCall) call.getOperands().get(0);
          switch(child.getOperator().getKind()) {
            case DIVIDE:
              if (!(RexUtil.isConstant(child.getOperands().get(1)) && child.getOperands().get(0) instanceof RexCall)) {
                return null;
              }
              RexCall gChild = (RexCall) child.getOperands().get(0);
              if (gChild.getOperands().size() != 2) {
                return null;
              }
              switch (gChild.getOperator().getKind()) {
                case PLUS:
                case MINUS:
                  return builder.makeCall(
                    call.getOperator(),
                    builder.makeCall(
                      gChild.getOperator(),
                      builder.makeCall(
                        child.getOperator(),
                        gChild.getOperands().get(0),
                        child.getOperands().get(1)
                      ),
                      builder.makeCall(
                        child.getOperator(),
                        gChild.getOperands().get(1),
                        child.getOperands().get(1)
                      )
                    )
                  );
                default:
                  return null;
              }
            default:
              return null;
          }
        default:
          return null;
      }
    }
  }

  /**
   * Rewrites expressions like cos(a - b) to a single call, cos_minus(a,b), to make it easier for the Expression pusher
   * detect if a and b are on separate sides of join, and thus the expression should be rewritten using trig identity
   */
  private static class SinCosPlusMinusRewriteRule extends RexRewriter.RewriteRule {

    public SinCosPlusMinusRewriteRule(RexBuilder builder) {
      super(builder);
    }

    @Override
    public RexNode rewrite(RexCall call) {
      switch(call.getOperator().getName().toUpperCase()) {
        case "SIN": {
          RexNode child = call.getOperands().get(0);
          if (!(child instanceof RexCall)) {
            return null;
          }
          RexCall childCall = (RexCall) child;
          switch (child.getKind()) {
            case PLUS: {
              return builder.makeCall(SIN_PLUS, childCall.getOperands().get(0), childCall.getOperands().get(1));
            }
            case MINUS: {
              return builder.makeCall(SIN_MINUS, childCall.getOperands().get(0), childCall.getOperands().get(1));
            }
          }
        }
        case "COS": {
          RexNode child = call.getOperands().get(0);
          if (!(child instanceof RexCall)) {
            return null;
          }
          RexCall childCall = (RexCall) child;
          switch (child.getKind()) {
            case PLUS: {
              return builder.makeCall(COS_PLUS, childCall.getOperands().get(0), childCall.getOperands().get(1));
            }
            case MINUS: {
              return builder.makeCall(COS_MINUS, childCall.getOperands().get(0), childCall.getOperands().get(1));
            }
          }
        }
        default:
          return null;
      }
    }
  }

  public static final SqlFunction SIN_PLUS =
    new SqlFunction(
      "SIN_PLUS",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.DOUBLE_NULLABLE,
      null,
      OperandTypes.NUMERIC,
      SqlFunctionCategory.NUMERIC);

  public static final SqlFunction SIN_MINUS =
    new SqlFunction(
      "SIN_MINUS",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.DOUBLE_NULLABLE,
      null,
      OperandTypes.NUMERIC,
      SqlFunctionCategory.NUMERIC);

  public static final SqlFunction COS_PLUS =
    new SqlFunction(
      "COS_PLUS",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.DOUBLE_NULLABLE,
      null,
      OperandTypes.NUMERIC,
      SqlFunctionCategory.NUMERIC);

  public static final SqlFunction COS_MINUS =
    new SqlFunction(
      "COS_MINUS",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.DOUBLE_NULLABLE,
      null,
      OperandTypes.NUMERIC,
      SqlFunctionCategory.NUMERIC);
}
