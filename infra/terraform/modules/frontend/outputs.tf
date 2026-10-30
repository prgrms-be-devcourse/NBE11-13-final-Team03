output "bucket_name" { value = aws_s3_bucket.this.bucket }
output "distribution_id" { value = aws_cloudfront_distribution.this.id }
output "function_names" { value = keys(aws_cloudfront_function.this) }

output "bucket_versioning_status" { value = aws_s3_bucket_versioning.this.versioning_configuration[0].status }
