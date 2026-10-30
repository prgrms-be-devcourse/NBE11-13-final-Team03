output "vpc_id" { value = aws_vpc.this.id }
output "subnet_ids" { value = { for key, subnet in aws_subnet.this : key => subnet.id } }
output "main_route_table_id" { value = aws_route_table.main.id }
output "vpc_default_route_table_id" { value = aws_vpc.this.default_route_table_id }
