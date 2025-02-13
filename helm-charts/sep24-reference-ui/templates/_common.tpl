{{- define "common.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "common.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "common.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "common.addField" -}}
{{- $key := index . 0 }}
{{- $val := index . 1 }}
{{- $tp := typeOf $val }}
{{- if eq $tp "map[string]interface {}" }}{{ printf "%s:\n" $key }}
{{- range $mapKey, $mapValue := $val }}
{{- printf "    %s: \"%v\"\n" $mapKey  $mapValue }}
{{- end }}
{{- else }}
{{- if toString $val }}
{{- printf "%s: \"%v\"\n" $key  $val -}}
{{- end }}
{{- end }}
{{- end }}

{{- define "common.addMultiline" -}}
{{- $key := index . 0 -}}
{{- $value := index . 1 -}}
{{ printf "%s: |" $key }}
{{ printf "%s" $value | indent 4 }}
{{- end }}