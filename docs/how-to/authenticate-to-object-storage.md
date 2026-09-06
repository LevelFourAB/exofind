# Authenticating to object storage

This guide shows you how to give a node the credentials it signs bucket
requests with in `object` storage mode: a fixed key pair, the role of the AWS
environment the node runs in, or a credentials file that something else
renews.

## Prerequisites

Before you begin, make sure that you have:

- A bucket on a storage that meets the
  [object storage requirements](../reference/object-storage.md).
- Nodes configured with `EXOFIND_STORAGE_MODE=object` and
  `EXOFIND_STORAGE_REMOTE_BUCKET`, and with `EXOFIND_STORAGE_REMOTE_URL` unless
  the bucket is on Amazon S3.

## Choose a credential source

`EXOFIND_STORAGE_REMOTE_AUTH` names where the node gets its credentials:

| Value | Credentials | Use it when |
| --- | --- | --- |
| `static` | The key pair in `EXOFIND_STORAGE_REMOTE_ACCESS_KEY` and `EXOFIND_STORAGE_REMOTE_SECRET_KEY`, with `EXOFIND_STORAGE_REMOTE_SESSION_TOKEN` when the pair was issued for a session. | The storage issues long-lived keys, or you pass temporary credentials in yourself. |
| `aws` | What the AWS environment hands the process: an instance role, a task role, or the role of a pod's service account. | The node runs on EC2, ECS, or EKS. |
| `file` | A profile in a file in the AWS credentials file format, named by `EXOFIND_STORAGE_REMOTE_CREDENTIALS_FILE`. The node reads the file again whenever it changes. | Something other than the node renews the credentials, such as a secrets agent or a rotation script. |

When `EXOFIND_STORAGE_REMOTE_AUTH` is unset, the node picks `static` when a key
pair is set, `file` when a credentials file is set, and `aws` when neither is.
The node refuses to start when the named source and the other settings
disagree, for example a key pair together with `aws`, or a key pair and a
credentials file with no source named.

Whichever source you choose, the node asks it for credentials once at startup
and refuses to start when it has none. After that, the source renews the
credentials on its own, and the node never restarts for a renewal.

## Option 1: Use a key pair

Use this option with a storage that issues long-lived keys, such as SeaweedFS,
MinIO, Cloudflare R2, or Google Cloud Storage through its HMAC keys.

1. Create a key pair in the storage with permission to list the bucket, and to
   read, write, and delete objects under the prefix the node uses.

   A node that only searches, such as a public demo node, works with a pair
   that can only list and read. For more information, see
   [Running a public demo node](run-a-demo-node.md).

2. Set the pair on every node:

   ```shell
   EXOFIND_STORAGE_MODE=object
   EXOFIND_STORAGE_REMOTE_URL=https://<account>.r2.cloudflarestorage.com
   EXOFIND_STORAGE_REMOTE_BUCKET=exofind
   EXOFIND_STORAGE_REMOTE_ACCESS_KEY=<access key>
   EXOFIND_STORAGE_REMOTE_SECRET_KEY=<secret key>
   ```

3. Start the nodes.

To pass in temporary credentials instead, such as a pair issued by AWS Security
Token Service (STS) or by the Cloudflare R2 temporary credentials API, set
`EXOFIND_STORAGE_REMOTE_SESSION_TOKEN` to the session token issued with the
pair. The node does not renew a pair it was given this way, so restart the node
with a new pair before the old one expires. To renew without a restart, use a
[credentials file](#option-3-use-a-credentials-file-that-something-else-renews).

## Option 2: Use the AWS environment

Use this option when the node runs on AWS and the bucket is on Amazon S3. The
node gets credentials that are scoped to its role and expire on their own, and
its configuration holds no secret.

1. Give the node a role that can reach the bucket:

   - On EC2, attach an instance profile.
   - On ECS, set a task role.
   - On EKS, annotate the service account of the pods with
     `eks.amazonaws.com/role-arn` for IAM Roles for Service Accounts, or use
     EKS Pod Identity.

2. Grant the role the following permissions:

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       {
         "Effect": "Allow",
         "Action": "s3:ListBucket",
         "Resource": "arn:aws:s3:::<bucket>"
       },
       {
         "Effect": "Allow",
         "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
         "Resource": "arn:aws:s3:::<bucket>/*"
       }
     ]
   }
   ```

   When the node uses `EXOFIND_STORAGE_REMOTE_PREFIX`, narrow the second
   resource to `arn:aws:s3:::<bucket>/<prefix>/*`.

3. Configure the nodes with no credential settings and no URL:

   ```shell
   EXOFIND_STORAGE_MODE=object
   EXOFIND_STORAGE_REMOTE_AUTH=aws
   EXOFIND_STORAGE_REMOTE_BUCKET=exofind
   EXOFIND_STORAGE_REMOTE_REGION=eu-north-1
   ```

   You can leave `EXOFIND_STORAGE_REMOTE_REGION` unset when the environment
   names the region, through `AWS_REGION` or the instance metadata. Set it
   when the bucket is in another region than the node.

4. Start the nodes.

The credentials come from the standard places, tried in this order: the
`AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` environment variables, the web
identity token file named by `AWS_WEB_IDENTITY_TOKEN_FILE`, the container
credentials endpoint, and the instance metadata service.

## Option 3: Use a credentials file that something else renews

Use this option when an agent outside the node fetches credentials and writes
them to disk: a secrets manager agent, a Kubernetes Secret that a controller
rotates, or a script that mints temporary credentials from the API of the
storage.

1. Have the agent write a file in the AWS credentials file format:

   ```ini
   [default]
   aws_access_key_id = <access key>
   aws_secret_access_key = <secret key>
   aws_session_token = <session token, when the pair has one>
   ```

   Write the file to a temporary name and rename it into place, so the node
   never reads a half-written file. A Kubernetes Secret mounted as a volume is
   updated this way by the kubelet. A Secret mounted with `subPath` is never
   updated.

2. Point the nodes at the file:

   ```shell
   EXOFIND_STORAGE_MODE=object
   EXOFIND_STORAGE_REMOTE_AUTH=file
   EXOFIND_STORAGE_REMOTE_CREDENTIALS_FILE=/run/secrets/storage/credentials
   EXOFIND_STORAGE_REMOTE_URL=https://<account>.r2.cloudflarestorage.com
   EXOFIND_STORAGE_REMOTE_BUCKET=exofind
   ```

   Set `EXOFIND_STORAGE_REMOTE_CREDENTIALS_PROFILE` when the file holds several
   profiles and the one to use is not named `default`.

3. Start the nodes.

The node reads the file again when its modification time changes, within a few
seconds of the change. Replace the file before the old credentials expire, so
that no request is signed with a pair the storage no longer accepts.

Instead of a key pair, a profile can name a command that prints credentials:

```ini
[default]
credential_process = /usr/local/bin/mint-storage-credentials
```

The node runs the command when it needs credentials, and again when the
credentials it printed expire. The command prints a JSON object with
`Version`, `AccessKeyId`, `SecretAccessKey`, `SessionToken`, and `Expiration`,
the format the AWS CLI reads from a `credential_process`.

## Verify the source

After a node starts, check the following:

1. Look for the startup log line that reads
   `Signing object storage requests with credentials from the named source`.
   Its `auth` field names the source the node picked.
2. Request `/q/health/ready`. The node answers `200 OK` after it reads the
   registry from the bucket.

A node that finds no credentials refuses to start, with a message that names
`EXOFIND_STORAGE_REMOTE_AUTH` and the source it tried.
