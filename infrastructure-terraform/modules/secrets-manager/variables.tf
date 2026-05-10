variable "name" {
  description = "Hierarchical secret name (e.g. shopease/development/auth-service)."
  type        = string
}

variable "description" {
  description = "Human-readable description of the secret."
  type        = string
}

variable "secret_data" {
  description = "Map of key-value pairs to store as JSON inside the secret."
  type        = map(string)
  sensitive   = true
}

variable "kms_key_id" {
  description = "KMS CMK ARN to encrypt the secret. If null, uses the default aws/secretsmanager key."
  type        = string
  default     = null
}

variable "recovery_window_in_days" {
  description = "Days to retain after deletion (7-30). Use 0 ONLY in dev for force-delete."
  type        = number
  default     = 30
}

variable "allowed_role_arns" {
  description = "IAM role ARNs allowed to GetSecretValue on this secret via resource policy. Empty list = no resource policy."
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Additional tags to apply to the secret."
  type        = map(string)
  default     = {}
}
