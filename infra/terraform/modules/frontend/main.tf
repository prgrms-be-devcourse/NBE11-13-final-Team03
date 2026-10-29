resource "aws_s3_bucket" "this" {
  bucket        = var.bucket.name
  tags          = var.bucket.tags
  force_destroy = false
  lifecycle { prevent_destroy = true }
}

resource "aws_s3_bucket_policy" "this" {
  for_each = var.bucket_controls.policy
  bucket   = var.bucket.name
  policy   = each.value.policy
  lifecycle { prevent_destroy = true }
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each                = var.bucket_controls.public_access
  bucket                  = var.bucket.name
  block_public_acls       = each.value.BlockPublicAcls
  block_public_policy     = each.value.BlockPublicPolicy
  ignore_public_acls      = each.value.IgnorePublicAcls
  restrict_public_buckets = each.value.RestrictPublicBuckets
  lifecycle { prevent_destroy = true }
}

resource "aws_s3_bucket_ownership_controls" "this" {
  for_each = var.bucket_controls.ownership
  bucket   = var.bucket.name
  rule { object_ownership = each.value.object_ownership }
  lifecycle { prevent_destroy = true }
}

resource "aws_s3_bucket_versioning" "this" {
  bucket = var.bucket.name
  versioning_configuration {
    status     = var.bucket.versioning_status
    mfa_delete = var.bucket.mfa_delete
  }
  lifecycle { prevent_destroy = true }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = var.bucket_controls.encryption
  bucket   = var.bucket.name
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

resource "aws_cloudfront_origin_access_control" "this" {
  name                              = var.oac.name
  description                       = var.oac.description
  origin_access_control_origin_type = var.oac.origin_type
  signing_behavior                  = var.oac.signing_behavior
  signing_protocol                  = var.oac.signing_protocol
  lifecycle { prevent_destroy = true }
}

resource "aws_cloudfront_function" "this" {
  for_each                     = var.functions
  name                         = each.key
  runtime                      = each.value.runtime
  comment                      = each.value.comment
  code                         = each.value.code
  publish                      = true
  key_value_store_associations = each.value.key_value_store_associations
  tags                         = each.value.tags
  lifecycle { prevent_destroy = true }
}

resource "aws_cloudfront_distribution" "this" {
  enabled                         = var.distribution.enabled
  is_ipv6_enabled                 = var.distribution.is_ipv6_enabled
  aliases                         = var.distribution.aliases
  comment                         = var.distribution.comment
  default_root_object             = var.distribution.default_root_object
  http_version                    = var.distribution.http_version
  price_class                     = var.distribution.price_class
  web_acl_id                      = var.distribution.web_acl_id
  continuous_deployment_policy_id = var.distribution.continuous_deployment_policy_id
  staging                         = var.distribution.staging
  tags                            = var.distribution.tags
  retain_on_delete                = true
  wait_for_deployment             = false
  dynamic "origin" {
    for_each = var.distribution.origins
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
    for_each = [var.distribution.default_cache_behavior]
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
    for_each = var.distribution.ordered_cache_behaviors
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
      restriction_type = var.distribution.geo_restriction.restriction_type
      locations        = var.distribution.geo_restriction.locations
    }
  }
  viewer_certificate {
    cloudfront_default_certificate = var.distribution.viewer_certificate.cloudfront_default_certificate
    acm_certificate_arn            = var.distribution.viewer_certificate.acm_certificate_arn
    iam_certificate_id             = var.distribution.viewer_certificate.iam_certificate_id
    minimum_protocol_version       = var.distribution.viewer_certificate.minimum_protocol_version
    ssl_support_method             = var.distribution.viewer_certificate.ssl_support_method
  }
  dynamic "custom_error_response" {
    for_each = var.distribution.custom_error_responses
    content {
      error_code            = custom_error_response.value.error_code
      response_code         = custom_error_response.value.response_code
      response_page_path    = custom_error_response.value.response_page_path
      error_caching_min_ttl = custom_error_response.value.error_caching_min_ttl
    }
  }
  dynamic "logging_config" {
    for_each = var.distribution.logging
    content {
      bucket          = logging_config.value.bucket
      prefix          = logging_config.value.prefix
      include_cookies = logging_config.value.include_cookies
    }
  }
  lifecycle { prevent_destroy = true }
}
