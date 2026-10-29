resource "aws_iam_role" "existing" {
  for_each             = var.adoption.roles
  name                 = each.key
  path                 = each.value.path
  description          = each.value.description
  max_session_duration = each.value.max_session_duration
  assume_role_policy   = each.value.assume_role_policy
  permissions_boundary = each.value.permissions_boundary
  tags                 = each.value.tags
  # Attachments and inline policies are independently imported below.
  lifecycle { prevent_destroy = true }
}

resource "aws_iam_instance_profile" "existing" {
  for_each = var.adoption.profiles
  name     = each.key
  path     = each.value.path
  role     = each.value.role
  tags     = each.value.tags
  lifecycle { prevent_destroy = true }
}

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
  role     = each.value.role
  policy   = each.value.policy
  lifecycle { prevent_destroy = true }
}

resource "aws_iam_role_policy_attachment" "existing" {
  for_each   = var.adoption.attachments
  role       = each.value.role
  policy_arn = each.value.policy_arn
  lifecycle { prevent_destroy = true }
}

resource "aws_iam_openid_connect_provider" "github" {
  url             = var.adoption.oidc.url
  client_id_list  = var.adoption.oidc.client_id_list
  thumbprint_list = var.adoption.oidc.thumbprint_list
  tags            = var.adoption.oidc.tags
  lifecycle { prevent_destroy = true }
}

import {
  for_each = var.adoption.roles
  to       = aws_iam_role.existing[each.key]
  id       = each.key
}

import {
  for_each = var.adoption.profiles
  to       = aws_iam_instance_profile.existing[each.key]
  id       = each.key
}

import {
  for_each = var.adoption.policies
  to       = aws_iam_policy.existing[each.key]
  id       = each.key
}

import {
  for_each = var.adoption.inline_policies
  to       = aws_iam_role_policy.existing[each.key]
  id       = "${each.value.role}:${each.value.name}"
}

import {
  for_each = var.adoption.attachments
  to       = aws_iam_role_policy_attachment.existing[each.key]
  id       = "${each.value.role}/${each.value.policy_arn}"
}

import {
  to = aws_iam_openid_connect_provider.github
  id = var.adoption.oidc.id
}
