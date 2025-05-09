package com.vik.ums

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories

@SpringBootApplication
@EnableReactiveMongoRepositories(basePackages = ["com.vik.ums.db.repositories"])
class UserManagementServiceApplication

fun main(args: Array<String>) {
	runApplication<UserManagementServiceApplication>(*args)
}
