package com.example.los.document.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.OAuthFlow;
import io.swagger.v3.oas.annotations.security.OAuthFlows;
import io.swagger.v3.oas.annotations.security.OAuthScope;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Configuration;

/**
 * The document service's contribution to the platform's OpenAPI document.
 *
 * <p>Deliberately thinner than the application service's. The published document
 * is the union of the two, and {@code OpenApiContractIT} takes the {@code info},
 * {@code servers} and top-level {@code security} from the application service —
 * so duplicating the platform-wide description here would produce two sources of
 * truth for the same prose, which is the problem this whole approach exists to
 * avoid. What this file declares is the security scheme its own operations
 * reference, and the tag its endpoints belong to.
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(title = "Loan Origination Platform API — documents", version = "1.0.0"),
        security = @SecurityRequirement(name = "oauth2"),
        tags = @Tag(name = "Documents", description = "Uploading supporting documents through short-lived presigned URLs."))
@SecurityScheme(
        name = "oauth2",
        type = SecuritySchemeType.OAUTH2,
        flows =
                @OAuthFlows(
                        clientCredentials =
                                @OAuthFlow(
                                        tokenUrl = "https://auth.example.com/oauth2/token",
                                        scopes = {
                                            @OAuthScope(
                                                    name = "documents:read",
                                                    description = "List documents on an application"),
                                            @OAuthScope(
                                                    name = "documents:write",
                                                    description = "Request upload slots and confirm uploads")
                                        })))
class OpenApiDocumentation {}
