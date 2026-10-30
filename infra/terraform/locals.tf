locals { network = jsondecode(file("${path.module}/../inventory/network-2026-10-28.json")) }

locals {
  role_names = {
    team03-main-sqs        = module.main.role_name
    team03-data-sqs        = module.data.role_name
    team03-frontend-deploy = module.github_oidc.role_name
  }
}
