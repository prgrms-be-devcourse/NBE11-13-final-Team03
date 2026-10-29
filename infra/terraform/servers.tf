module "main" {
  source         = "./modules/server"
  instance       = var.adoption.instances.main
  vpc_id         = module.network.vpc_id
  security_group = local.network.security_groups.main
  ingress        = { for key, rule in local.network.ingress_rules : key => rule if rule.group == "main" }
  egress         = local.network.egress_rules.main
  peer_sg_ids    = {}
  instance_role  = merge(var.adoption.roles["team03-main-sqs"], { name = "team03-main-sqs" })
  profile        = merge(var.adoption.profiles["team03-main-sqs"], { name = "team03-main-sqs" })
  eip            = var.adoption.eip
}

module "data" {
  source         = "./modules/server"
  instance       = var.adoption.instances.data
  vpc_id         = module.network.vpc_id
  security_group = local.network.security_groups.data
  ingress        = { for key, rule in local.network.ingress_rules : key => rule if rule.group == "data" }
  egress         = local.network.egress_rules.data
  peer_sg_ids    = { main = module.main.sg_id }
  instance_role  = merge(var.adoption.roles["team03-data-sqs"], { name = "team03-data-sqs" })
  profile        = merge(var.adoption.profiles["team03-data-sqs"], { name = "team03-data-sqs" })
  eip            = null
}

module "db" {
  source         = "./modules/server"
  instance       = var.adoption.instances.db
  vpc_id         = module.network.vpc_id
  security_group = local.network.security_groups.db
  ingress        = { for key, rule in local.network.ingress_rules : key => rule if rule.group == "db" }
  egress         = local.network.egress_rules.db
  peer_sg_ids    = { main = module.main.sg_id, data = module.data.sg_id, replica = module.replica.sg_id }
  instance_role  = null
  profile        = null
  eip            = null
}

module "replica" {
  source         = "./modules/server"
  instance       = var.adoption.instances.replica
  vpc_id         = module.network.vpc_id
  security_group = local.network.security_groups.replica
  ingress        = { for key, rule in local.network.ingress_rules : key => rule if rule.group == "replica" }
  egress         = local.network.egress_rules.replica
  peer_sg_ids    = { main = module.main.sg_id }
  instance_role  = null
  profile        = null
  eip            = null
}
