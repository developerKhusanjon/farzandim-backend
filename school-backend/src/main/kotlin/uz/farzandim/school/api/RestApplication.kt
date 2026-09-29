package uz.farzandim.school.api

import jakarta.ws.rs.ApplicationPath
import jakarta.ws.rs.core.Application
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition
import org.eclipse.microprofile.openapi.annotations.info.Info
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/** Barcha endpointlar `/api/v1` ostida — docs/03-api-contract.md. */
@ApplicationPath("/api/v1")
@OpenAPIDefinition(
    info = Info(
        title = "Farzandim AI — school-backend",
        version = "1.0.0",
        description = "Maktab CRM API: admin va o'qituvchi. Shartnoma: docs/03-api-contract.md",
    ),
    tags = [
        Tag(name = "auth"), Tag(name = "school"), Tag(name = "people"),
        Tag(name = "academic"), Tag(name = "homework"), Tag(name = "ai"),
    ],
)
class RestApplication : Application()
