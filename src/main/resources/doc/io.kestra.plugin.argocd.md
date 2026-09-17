# How to use the ArgoCD plugin

Create, sync, inspect, patch and delete ArgoCD applications from Kestra flows using the ArgoCD CLI.

## Authentication

Set `server` to your ArgoCD API server URL and `token` to a bearer token for ArgoCD CLI authentication. Set `application` to the target ArgoCD application name. Store `token` in a [secret](https://kestra.io/docs/concepts/secret).

By default TLS verification is enabled (`insecure: false`); set `insecure: true` to skip certificate validation, or set `serverCert` to a PEM-encoded certificate to verify a self-signed or custom-CA server. For gRPC connections through proxies, set `grpcWeb: true`. The plugin runs the ArgoCD CLI inside a container (`containerImage` defaults to `curlimages/curl:latest`); use `taskRunner` to control where the container runs.

## Tasks

`apps.Sync` synchronizes an application — equivalent to `argocd app sync`. Set `revision` to target a specific Git commit, tag, or branch. Set `prune: true` to remove resources no longer in Git, `dryRun: true` to preview changes, and `force: true` to recreate resources if needed. The output includes `syncStatus`, `healthStatus`, and a `resources` list with per-resource Kubernetes status.

`apps.Status` fetches current application status — equivalent to `argocd app get`. Set `refresh: true` to bypass the cache and re-query the cluster. The output includes `syncStatus`, `healthStatus`, `conditions`, and `resources`.

`apps.Create` declares an application, equivalent to `argocd app create`. Either set `repo`, `path`, `revision` and the destination (`destServer` or `destName`, `destNamespace`), or set `manifest` with a full Application resource. `manifest` accepts inline YAML/JSON or a `kestra://`, `file://` or `nsfile://` URI, and the other source properties are ignored when it is set. Set `syncPolicy: AUTOMATED` with `autoPrune` and `selfHeal` to let ArgoCD sync on its own, and `upsert: true` to update an application that already exists. The output includes `action`, which is `created`, `updated`, or `unchanged`.

`apps.Patch` updates an application spec, equivalent to `argocd app patch`. Set `patch` to a RFC 6902 JSON patch (the default) or to a RFC 7386 merge patch with `patchType: MERGE`. Like `manifest`, `patch` accepts inline content or a file URI. The output includes `syncStatus`, `healthStatus`, and the patched `spec`.

`apps.Delete` removes an application, equivalent to `argocd app delete`. The deletion is cascaded by default, so the resources managed by the application are deleted too; set `cascade: false` to keep them on the cluster. Use `propagationPolicy` to choose between `FOREGROUND` and `BACKGROUND`, and `wait: true` to block until the deletion completes. The confirmation prompt is always turned off.
