variable "adoption" {
  description = "Private inputs produced by prepare_import.py from a complete --scope import inventory."
  type        = any
  nullable    = false
  validation {
    condition     = var.adoption.account_id == "174170816230" && var.adoption.region == "ap-northeast-2" && length(var.adoption.roles) == 3 && length(var.adoption.instances) == 4
    error_message = "A complete YON adoption inventory is required; do not supply empty placeholder values."
  }
}
