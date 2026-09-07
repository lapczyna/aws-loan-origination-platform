terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
      # The DR replica lives in another region, which needs a second provider
      # instance. Declaring the alias here makes the requirement explicit: a
      # caller that enables the replica without passing a dr provider gets a
      # clear error rather than a replica created in the wrong region.
      configuration_aliases = [aws.dr]
    }
  }
}
