# Worker MinIO Policy

Provision a dedicated MinIO access key for the worker with object-level permissions only.

## Provisioning commands

Run on the server with `mc` configured against the MinIO instance:

```bash
mc admin user add minio worker-access <strong-secret>
mc admin policy create minio worker-policy worker-policy.json
mc admin policy attach minio worker-policy --user worker-access
```

The worker Docker Compose service uses `WORKER_MINIO_ACCESS_KEY` and `WORKER_MINIO_SECRET_KEY`
environment variables (separate from the API's `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`).
The worker module's `MinioConfig` uses these to construct its `MinioClient` bean.

## Why sub-prefix scoping (SA3-F2)

Restricting to `originals/*` and `thumbnails/*` means even a fully compromised worker
credential cannot delete arbitrary bucket contents (e.g., other tenants' top-level objects
or infrastructure backups). This limits the blast radius of a Redis-compromise or
container-escape scenario at the IAM level — independent of application-level validation.
