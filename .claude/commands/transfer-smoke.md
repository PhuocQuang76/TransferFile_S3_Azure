---
description: End-to-end smoke test — upload a file to S3, trigger a transfer, confirm the result
allowed-tools: Bash, Read
---

Run an end-to-end check against the service at `http://localhost:8586` (port-forward first if it is running in Kubernetes).

1. Confirm it is up: `curl -s http://localhost:8586/actuator/health`
2. Create a uniquely named test file in the scratchpad directory and upload it:
   `aws s3 cp <file> s3://aileen-bucket-011243863866/<name>` — the bucket name comes from the `s3_bucket_name` key in the Secrets Manager secret, so confirm it matches what the running instance logged at startup.
3. Trigger the transfer: `curl -s -X POST "http://localhost:8586/api/v1/transfer?overwrite=true"` and pretty-print the `TransferSummary`.
4. Interpret the result: 200 means no failures, 207 means partial, 500 means everything failed. Report `totalDiscovered` / `totalTransferred` / `totalSkipped` / `totalFailed`, and for any `FAILED` entry quote its `errorCode` and `errorMessage`.
5. If anything failed, check `./logs/filetransfer-error.log` (or `kubectl logs`) for the cause.

Note that `transfer.delete-after-transfer` defaults to `true` in the dev and prod profiles — the smoke test file will be removed from S3 on success. Say so in the report. Do not upload anything other than the throwaway test file you created.
