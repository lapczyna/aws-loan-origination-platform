{{/*
Shared naming and labelling helpers.

Kept in one place so that every object the chart creates carries an identical
label set. Consistent labels are what make a selector, a NetworkPolicy, a PDB
and a dashboard query agree with each other; when they drift, a PDB silently
protects nothing and a NetworkPolicy silently allows everything.
*/}}

{{- define "los.fullname" -}}
{{- printf "%s-%s" .root.Release.Name .serviceName | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Labels applied to every object. app.kubernetes.io/* are the standard set that
tooling recognises; the rest are ours.
*/}}
{{- define "los.labels" -}}
app.kubernetes.io/name: {{ .serviceName }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
app.kubernetes.io/component: service
app.kubernetes.io/part-of: loan-origination-platform
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version | replace "+" "_" }}
los.example.com/environment: {{ .root.Values.environment }}
{{- end -}}

{{/*
Selector labels: the subset that must NEVER change for a running release.

A Deployment's selector is immutable, so anything in here is effectively
permanent. Version and chart labels are deliberately excluded -- including them
would make every version bump an unschedulable update.
*/}}
{{- define "los.selectorLabels" -}}
app.kubernetes.io/name: {{ .serviceName }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{/*
The service account name. One per service, never shared: each carries its own
IAM role, and a shared account would give every pod the union of all four
services' AWS permissions.
*/}}
{{- define "los.serviceAccountName" -}}
{{- printf "%s-%s" .root.Release.Name .serviceName | trunc 63 | trimSuffix "-" -}}
{{- end -}}
