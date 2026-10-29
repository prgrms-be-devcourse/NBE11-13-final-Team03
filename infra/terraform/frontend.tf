module "frontend" {
  source          = "./modules/frontend"
  bucket          = var.adoption.bucket
  bucket_controls = var.adoption.bucket_controls
  oac             = var.adoption.oac
  functions       = var.adoption.functions
  distribution    = var.adoption.distribution
}
