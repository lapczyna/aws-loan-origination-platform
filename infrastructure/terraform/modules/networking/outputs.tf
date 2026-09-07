output "vpc_id" {
  description = "VPC id."
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "VPC CIDR, used by NetworkPolicies and security group rules."
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "Public subnets: NAT gateways and load balancer interfaces only."
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "Private subnets: EKS nodes and application workloads."
  value       = aws_subnet.private[*].id
}

output "data_subnet_ids" {
  description = "Data subnets: RDS and MSK. No route to a NAT gateway, so no egress at all."
  value       = aws_subnet.data[*].id
}

output "nat_gateway_public_ips" {
  description = "NAT gateway addresses, for allow-listing with an external provider."
  value       = aws_eip.nat[*].public_ip
}
