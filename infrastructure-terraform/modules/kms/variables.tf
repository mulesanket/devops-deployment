variable "alias" {
  description = "KMS alias suffix (without 'alias/' prefix). Final alias becomes alias/<this>."
  type        = string
}

variable "description" {
  description = "Human-readable description of the KMS key."
  type        = string
}

variable "deletion_window_in_days" {
  description = "Days to wait before final deletion (7-30). Higher = safer."
  type        = number
  default     = 30
}

variable "enable_key_rotation" {
  description = "Enable automatic annual rotation of the underlying key material."
  type        = bool
  default     = true
}

variable "tags" {
  description = "Additional tags to apply to the KMS key."
  type        = map(string)
  default     = {}
}
