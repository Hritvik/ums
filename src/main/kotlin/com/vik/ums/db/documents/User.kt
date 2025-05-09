package com.vik.ums.db.documents

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document


@Document(collection = "users")
data class User(
    @Id val id: String? = null,
    val tenantId: String,
    val name: String,
    val email: String,
    val phone: String
)