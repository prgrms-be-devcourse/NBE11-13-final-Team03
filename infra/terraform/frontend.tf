resource "aws_s3_bucket" "frontend" {
  bucket        = var.adoption.bucket.name
  tags          = var.adoption.bucket.tags
  force_destroy = false
  lifecycle { prevent_destroy = true }
}

resource "aws_s3_bucket_policy" "frontend" {
  for_each = var.adoption.bucket_controls.policy
  bucket   = var.adoption.bucket.name
  policy   = each.value.policy
  lifecycle { prevent_destroy = true }
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  for_each                = var.adoption.bucket_controls.public_access
  bucket                  = var.adoption.bucket.name
  block_public_acls       = each.value.BlockPublicAcls
  block_public_policy     = each.value.BlockPublicPolicy
  ignore_public_acls      = each.value.IgnorePublicAcls
  restrict_public_buckets = each.value.RestrictPublicBuckets
  lifecycle { prevent_destroy = true }
}

resource "aws_s3_bucket_ownership_controls" "frontend" {
  for_each = var.adoption.bucket_controls.ownership
  bucket   = var.adoption.bucket.name
  rule { object_ownership = each.value.object_ownership }
  lifecycle { prevent_destroy = true }
}

resource "aws_s3_bucket_versioning" "frontend" {
  bucket = var.adoption.bucket.name
  versioning_configuration {
    status     = var.adoption.bucket.versioning_status
    mfa_delete = var.adoption.bucket.mfa_delete
  }
  lifecycle { prevent_destroy = true }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "frontend" {
  for_each = var.adoption.bucket_controls.encryption
  bucket   = var.adoption.bucket.name
  dynamic "rule" {
    for_each = each.value.rules
    content {
      bucket_key_enabled       = rule.value.bucket_key_enabled
      blocked_encryption_types = rule.value.blocked_encryption_types
      apply_server_side_encryption_by_default {
        sse_algorithm     = rule.value.sse_algorithm
        kms_master_key_id = rule.value.kms_master_key_id
      }
    }
  }
  lifecycle { prevent_destroy = true }
}

resource "aws_cloudfront_origin_access_control" "frontend" {
  name                              = var.adoption.oac.name
  description                       = var.adoption.oac.description
  origin_access_control_origin_type = var.adoption.oac.origin_type
  signing_behavior                  = var.adoption.oac.signing_behavior
  signing_protocol                  = var.adoption.oac.signing_protocol
  lifecycle { prevent_destroy = true }
}

resource "aws_cloudfront_function" "existing" {
  for_each                     = var.adoption.functions
  name                         = each.key
  runtime                      = each.value.runtime
  comment                      = each.value.comment
  code                         = each.value.code
  publish                      = true
  key_value_store_associations = each.value.key_value_store_associations
  tags                         = each.value.tags
  lifecycle { prevent_destroy = true }
}

resource "aws_cloudfront_distribution" "frontend" {
  enabled                         = var.adoption.distribution.enabled
  is_ipv6_enabled                 = var.adoption.distribution.is_ipv6_enabled
  aliases                         = var.adoption.distribution.aliases
  comment                         = var.adoption.distribution.comment
  default_root_object             = var.adoption.distribution.default_root_object
  http_version                    = var.adoption.distribution.http_version
  price_class                     = var.adoption.distribution.price_class
  web_acl_id                      = var.adoption.distribution.web_acl_id
  continuous_deployment_policy_id = var.adoption.distribution.continuous_deployment_policy_id
  staging                         = var.adoption.distribution.staging
  tags                            = var.adoption.distribution.tags
  retain_on_delete                = true
  wait_for_deployment             = false
  dynamic "origin" {
    for_each = var.adoption.distribution.origins
    content {
      origin_id                   = origin.value.origin_id
      domain_name                 = origin.value.domain_name
      origin_path                 = origin.value.origin_path
      origin_access_control_id    = origin.value.origin_access_control_id
      connection_attempts         = origin.value.connection_attempts
      connection_timeout          = origin.value.connection_timeout
      response_completion_timeout = origin.value.response_completion_timeout
      dynamic "custom_header" {
        for_each = origin.value.custom_headers
        content {
          name  = custom_header.value.name
          value = custom_header.value.value
        }
      }
      dynamic "custom_origin_config" {
        for_each = origin.value.custom_origin_config
        content {
          http_port                = custom_origin_config.value.http_port
          https_port               = custom_origin_config.value.https_port
          ip_address_type          = custom_origin_config.value.ip_address_type
          origin_protocol_policy   = custom_origin_config.value.origin_protocol_policy
          origin_ssl_protocols     = custom_origin_config.value.origin_ssl_protocols
          origin_read_timeout      = custom_origin_config.value.origin_read_timeout
          origin_keepalive_timeout = custom_origin_config.value.origin_keepalive_timeout
        }
      }
      dynamic "s3_origin_config" {
        for_each = origin.value.s3_origin_config
        content { origin_access_identity = s3_origin_config.value.origin_access_identity }
      }
      dynamic "origin_shield" {
        for_each = origin.value.origin_shield
        content {
          enabled              = origin_shield.value.enabled
          origin_shield_region = origin_shield.value.origin_shield_region
        }
      }
    }
  }
  dynamic "default_cache_behavior" {
    for_each = [var.adoption.distribution.default_cache_behavior]
    iterator = behavior
    content {
      target_origin_id           = behavior.value.target_origin_id
      allowed_methods            = behavior.value.allowed_methods
      cached_methods             = behavior.value.cached_methods
      viewer_protocol_policy     = behavior.value.viewer_protocol_policy
      cache_policy_id            = behavior.value.cache_policy_id
      origin_request_policy_id   = behavior.value.origin_request_policy_id
      response_headers_policy_id = behavior.value.response_headers_policy_id
      realtime_log_config_arn    = behavior.value.realtime_log_config_arn
      field_level_encryption_id  = behavior.value.field_level_encryption_id
      compress                   = behavior.value.compress
      smooth_streaming           = behavior.value.smooth_streaming
      trusted_key_groups         = behavior.value.trusted_key_groups
      trusted_signers            = behavior.value.trusted_signers
      min_ttl                    = behavior.value.min_ttl
      default_ttl                = behavior.value.default_ttl
      max_ttl                    = behavior.value.max_ttl
      grpc_config { enabled = behavior.value.grpc_enabled }
      dynamic "function_association" {
        for_each = behavior.value.functions
        content {
          event_type   = function_association.value.event_type
          function_arn = function_association.value.function_arn
        }
      }
      dynamic "lambda_function_association" {
        for_each = behavior.value.lambdas
        content {
          event_type   = lambda_function_association.value.event_type
          lambda_arn   = lambda_function_association.value.lambda_arn
          include_body = lambda_function_association.value.include_body
        }
      }
    }
  }
  dynamic "ordered_cache_behavior" {
    for_each = var.adoption.distribution.ordered_cache_behaviors
    iterator = behavior
    content {
      path_pattern               = behavior.value.path_pattern
      target_origin_id           = behavior.value.target_origin_id
      allowed_methods            = behavior.value.allowed_methods
      cached_methods             = behavior.value.cached_methods
      viewer_protocol_policy     = behavior.value.viewer_protocol_policy
      cache_policy_id            = behavior.value.cache_policy_id
      origin_request_policy_id   = behavior.value.origin_request_policy_id
      response_headers_policy_id = behavior.value.response_headers_policy_id
      realtime_log_config_arn    = behavior.value.realtime_log_config_arn
      field_level_encryption_id  = behavior.value.field_level_encryption_id
      compress                   = behavior.value.compress
      smooth_streaming           = behavior.value.smooth_streaming
      trusted_key_groups         = behavior.value.trusted_key_groups
      trusted_signers            = behavior.value.trusted_signers
      min_ttl                    = behavior.value.min_ttl
      default_ttl                = behavior.value.default_ttl
      max_ttl                    = behavior.value.max_ttl
      grpc_config { enabled = behavior.value.grpc_enabled }
      dynamic "function_association" {
        for_each = behavior.value.functions
        content {
          event_type   = function_association.value.event_type
          function_arn = function_association.value.function_arn
        }
      }
      dynamic "lambda_function_association" {
        for_each = behavior.value.lambdas
        content {
          event_type   = lambda_function_association.value.event_type
          lambda_arn   = lambda_function_association.value.lambda_arn
          include_body = lambda_function_association.value.include_body
        }
      }
    }
  }
  restrictions {
    geo_restriction {
      restriction_type = var.adoption.distribution.geo_restriction.restriction_type
      locations        = var.adoption.distribution.geo_restriction.locations
    }
  }
  viewer_certificate {
    cloudfront_default_certificate = var.adoption.distribution.viewer_certificate.cloudfront_default_certificate
    acm_certificate_arn            = var.adoption.distribution.viewer_certificate.acm_certificate_arn
    iam_certificate_id             = var.adoption.distribution.viewer_certificate.iam_certificate_id
    minimum_protocol_version       = var.adoption.distribution.viewer_certificate.minimum_protocol_version
    ssl_support_method             = var.adoption.distribution.viewer_certificate.ssl_support_method
  }
  dynamic "custom_error_response" {
    for_each = var.adoption.distribution.custom_error_responses
    content {
      error_code            = custom_error_response.value.error_code
      response_code         = custom_error_response.value.response_code
      response_page_path    = custom_error_response.value.response_page_path
      error_caching_min_ttl = custom_error_response.value.error_caching_min_ttl
    }
  }
  dynamic "logging_config" {
    for_each = var.adoption.distribution.logging
    content {
      bucket          = logging_config.value.bucket
      prefix          = logging_config.value.prefix
      include_cookies = logging_config.value.include_cookies
    }
  }
  lifecycle { prevent_destroy = true }
}

import {
  to = aws_s3_bucket.frontend
  id = var.adoption.bucket.name
}
import {
  for_each = var.adoption.bucket_controls.policy
  to       = aws_s3_bucket_policy.frontend[each.key]
  id       = var.adoption.bucket.name
}
import {
  for_each = var.adoption.bucket_controls.public_access
  to       = aws_s3_bucket_public_access_block.frontend[each.key]
  id       = var.adoption.bucket.name
}
import {
  for_each = var.adoption.bucket_controls.ownership
  to       = aws_s3_bucket_ownership_controls.frontend[each.key]
  id       = var.adoption.bucket.name
}
import {
  to = aws_s3_bucket_versioning.frontend
  id = var.adoption.bucket.name
}
import {
  for_each = var.adoption.bucket_controls.encryption
  to       = aws_s3_bucket_server_side_encryption_configuration.frontend[each.key]
  id       = var.adoption.bucket.name
}
import {
  to = aws_cloudfront_origin_access_control.frontend
  id = var.adoption.oac.id
}
import {
  for_each = var.adoption.functions
  to       = aws_cloudfront_function.existing[each.key]
  id       = each.key
}
import {
  to = aws_cloudfront_distribution.frontend
  id = var.adoption.distribution.id
}
