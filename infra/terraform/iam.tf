# Keep dynamically discovered policy keys at their existing state addresses.
# This avoids needing private production policy/attachment names for moved blocks.
resource "aws_iam_policy" "existing" {
  for_each    = var.adoption.policies
  name        = each.value.name
  path        = each.value.path
  description = each.value.description
  policy      = each.value.policy
  tags        = each.value.tags
  lifecycle { prevent_destroy = true }
}

resource "aws_iam_role_policy" "existing" {
  for_each = var.adoption.inline_policies
  name     = each.value.name
  role     = local.role_names[each.value.role]
  policy   = each.value.policy
  lifecycle { prevent_destroy = true }
}

resource "aws_iam_role_policy_attachment" "existing" {
  for_each   = var.adoption.attachments
  role       = local.role_names[each.value.role]
  policy_arn = each.value.policy_arn
  lifecycle { prevent_destroy = true }
}
