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

export const CLUSTER_STATE = {
  running: 'RUNNING', stopped: 'STOPPED', starting: 'STARTING', stopping: 'STOPPING', deleted: 'DELETED'
};

export const EC2_CLUSTER_FIELDS = ['name'];
export const EC2_DYNAMIC_CONFIG_FIELDS = ['containerCount'];
export const EC2_AWS_PROPS = [
  'vpc', 'nodeIamInstanceProfile', 'amiId', 'sshKeyName', 'securityGroupId', 'subnetId', 'instanceType',
  'extraConfProps', 'useClusterPlacementGroup'
];
export const EC2_AWS_CONNECTION_PROPS = [
  'authMode', 'accessKey', 'secretKey', 'region', 'endpoint', 'assumeRole'
];
export const EC2_FIELDS = [...EC2_CLUSTER_FIELDS, ...EC2_DYNAMIC_CONFIG_FIELDS, ...EC2_AWS_PROPS, ...EC2_AWS_CONNECTION_PROPS];
export const EC2_FIELDS_MAP = EC2_FIELDS.reduce((a, field) => {
  a[field] = field;
  return a;
}, {});

export const DREMIO_CUSTOM_REGION = '$DREMIO_CUSTOM_ENDPOINT_URL$';

export const AWS_REGION_OPTIONS = [
  {label: 'US East (N. Virginia)', value: 'us-east-1'},
  {label: 'US West (N. California)', value: 'us-west-1'},
  {label: 'US West (Oregon)', value: 'us-west-2'},
  {label: 'EU (Ireland)', value: 'eu-west-1'},
  {label: 'Asia Pacific (Singapore)', value: 'ap-southeast-1'}
];

export const AWS_INSTANCE_TYPE_OPTIONS = [
  {label: 'm5d.8xlarge (32c/128gb)', value: 'm5d.8xlarge'},
  {label: 'r5d.4xlarge (16c/128gb)', value: 'r5d.4xlarge'},
  {label: 'c5d.18xlarge (72c/144gb)', value: 'c5d.18xlarge'},
  {label: 'r5ad.4xlarge (16c/128gb)', value: 'r5ad.4xlarge'},
  {label: 'i3.4xlarge (16c/122gb)', value: 'i3.4xlarge'}
];

//TODO use field constants in VLH
export const EC2_FORM_TAB_VLH = {
  sections: [
    {
      name: ' ',
      layout: 'row',
      elements: [
        {
          type: 'text',
          propName: 'name',
          propertyName: 'name',
          tooltip: 'Name of the engine',
          size: 'half',
          label: 'Name'
        },
        {
          type: 'container_selection',
          selectorType: 'select',
          propName: 'region',
          propertyName: 'region',
          label: 'Region',
          tooltip: 'AWS Region to deploy engine',
          size: 'full',
          options: [...AWS_REGION_OPTIONS.map(region => ({...region, container: {}})),
            {
              label: 'Endpoint Override',
              value: '$DREMIO_CUSTOM_ENDPOINT_URL$',
              container: {
                type: 'text',
                propName: 'endpoint',
                propertyName: 'endpoint',
                label: 'Endpoint'
              }
            }
          ]
        },
        {
          type: 'select',
          propName: 'instanceType',
          propertyName: 'instanceType',
          label: 'Instance Type',
          tooltip: 'AWS Instance type used to execution nodes',
          size: 'half',
          options: AWS_INSTANCE_TYPE_OPTIONS
        },
        {
          type: 'number',
          propName: 'containerCount',
          propertyName: 'containerCount',
          label: 'Instance Count',
          tooltip: 'Number of execution nodes',
          size: 'half'
        }
      ]
    },
    {
      name: '',
      elements: [
        {
          type: 'text',
          propName: 'sshKeyName',
          propertyName: 'sshKeyName',
          size: 'half',
          tooltip: 'The AWS Key pair name used to log onto server instances',
          label: 'EC2 Key Pair Name'
        },
        {
          type: 'text',
          propName: 'securityGroupId',
          propertyName: 'securityGroupId',
          size: 'half',
          tooltip: 'The Group ID of the Security group to use, e.g. “sg-0e7662f1d7a81abff”',
          label: 'Security Group ID'
        },
        {
          type: 'text',
          propName: 'nodeIamInstanceProfile',
          propertyName: 'nodeIamInstanceProfile',
          size: 'half',
          tooltip: 'The IAM role used to access S3 buckets',
          label: 'IAM Role for S3 Access'
        }
      ]
    },
    {
      name: 'Advanced Properties',
      collapsible: {initCollapsed: true},
      elements: [
        {
          type: 'checkbox',
          propName: 'useClusterPlacementGroup',
          propertyName: 'useClusterPlacementGroup',
          tooltip: 'Whether or not to use placement groups which locates instances close together inside an Availability Zone (optional)',
          label: 'Use Clustered Placement'
        },
        {
          type: 'text',
          propName: 'vpc',
          propertyName: 'vpc',
          size: 'half',
          tooltip: 'The VPC ID of the Virtual Private Network to run in, e.g. vpc-04ee018cabe5a30ac (optional)',
          label: 'VPC'
        },
        {
          type: 'text',
          propName: 'subnetId',
          propertyName: 'subnetId',
          size: 'half',
          tooltip: 'The Subnet ID within the VPN to run within, e.g. subnet-0b18cf53f02d3bf0e (optional)',
          label: 'Subnet ID'
        },
        {
          type: 'text',
          propName: 'amiId',
          propertyName: 'amiId',
          size: 'half',
          tooltip: 'The AMI used for execution and coordinator nodes, e.g. ami-00002c141987d373c (optional)',
          label: 'AMI Identifier'
        },
        {
          type: 'container_selection',
          propName: 'authMode',
          propertyName: 'authMode',
          label: 'AWS Api Authentication Mode',
          options: [
            {
              value: 'AUTO',
              label: 'Auto',
              container: {}
            },
            {
              value: 'SECRET',
              label: 'Key / Secret',
              container: {
                layout: 'row',
                elements: [
                  {
                    type: 'text',
                    propName: 'accessKey',
                    propertyName: 'accessKey',
                    label: 'Access Key',
                    size: 'half',
                    errMsg: 'Both access secret and key are required.'
                  },
                  {
                    type: 'text',
                    propName: 'secretKey',
                    propertyName: 'secretKey',
                    label: 'Secret',
                    size: 'half',
                    secure: true,
                    errMsg: 'Both access secret and key are required.'
                  }
                ]
              }
            }
          ]
        },
        {
          type: 'text',
          propName: 'assumeRole',
          propertyName: 'assumeRole',
          tooltip: 'The IAM role used by Dremio to manage the engine (optional)',
          label: 'IAM Role for API Operations'
        },
        {
          type: 'textarea',
          propName: 'extraConfProps',
          propertyName: 'extraConfProps',
          tooltip: 'Additional Dremio configuration options (optional)',
          label: 'Extra Dremio Configuration Properties'
        }
      ]
    }
  ]
};
