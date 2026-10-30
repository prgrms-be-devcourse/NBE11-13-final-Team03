resource "aws_iam_openid_connect_provider" "this" {
  url             = var.oidc.url
  client_id_list  = var.oidc.client_id_list
  thumbprint_list = var.oidc.thumbprint_list
  tags            = var.oidc.tags
  lifecycle { prevent_destroy = true }
}

resource "aws_iam_role" "this" {
  name                 = var.role.name
  path                 = var.role.path
  description          = var.role.description
  max_session_duration = var.role.max_session_duration
  assume_role_policy   = var.role.assume_role_policy
  permissions_boundary = var.role.permissions_boundary
  tags                 = var.role.tags
  # Attachments and inline policies are independently imported below.
  lifecycle { prevent_destroy = true }
}
