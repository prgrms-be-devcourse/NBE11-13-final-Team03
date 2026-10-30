output "instance_id" { value = aws_instance.this.id }
output "sg_id" { value = aws_security_group.this.id }
output "public_dns" { value = aws_instance.this.public_dns }
output "role_arn" { value = try(aws_iam_role.this[0].arn, null) }
output "role_name" { value = try(aws_iam_role.this[0].name, null) }
