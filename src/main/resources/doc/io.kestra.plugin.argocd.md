# How to use the ArgoCD plugin

Sync and inspect ArgoCD applications from Kestra flows using the ArgoCD CLI.

## Authentication

Set `server` to your ArgoCD API server URL and `token` to a bearer token for ArgoCD CLI authentication. Set `application` to the target ArgoCD application name. Store `token` in a [secret](https://kestra.io/docs/concepts/secret).

By default TLS verification is skipped (`insecure: true`) — set `serverCert` to a PEM-encoded certificate to verify the server instead. For gRPC connections through proxies, set `grpcWeb: true`. The plugin runs the ArgoCD CLI inside a container (`containerImage` defaults to `curlimages/curl:latest`); use `taskRunner` to control where the container runs.

## Tasks

`apps.Sync` synchronizes an application — equivalent to `argocd app sync`. Set `revision` to target a specific Git commit, tag, or branch. Set `prune: true` to remove resources no longer in Git, `dryRun: true` to preview changes, and `force: true` to recreate resources if needed. The output includes `syncStatus`, `healthStatus`, and a `resources` list with per-resource Kubernetes status.

`apps.Status` fetches current application status — equivalent to `argocd app get`. Set `refresh: true` to bypass the cache and re-query the cluster. The output includes `syncStatus`, `healthStatus`, `conditions`, and `resources`.
