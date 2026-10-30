module "github_oidc" {
  source = "./modules/github_oidc"
  oidc   = var.adoption.oidc
  role   = merge(var.adoption.roles["team03-frontend-deploy"], { name = "team03-frontend-deploy" })
}
