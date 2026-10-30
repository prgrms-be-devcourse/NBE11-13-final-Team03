resource "aws_key_pair" "team" {
  key_name   = var.adoption.key_pair.key_name
  public_key = var.adoption.key_pair.public_key
  tags       = var.adoption.key_pair.tags
  lifecycle {
    prevent_destroy = true
    # Some imported key pairs do not round-trip public_key. Check fingerprint.
    ignore_changes = [public_key]
  }
}
