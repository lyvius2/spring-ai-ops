package com.walter.spring.ai.ops.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.mock.env.MockEnvironment

class RepositoryPropertiesTest {

    @Test
    @DisplayName("repository 설정이 ConfigurationProperties로 바인딩된다")
    fun givenRepositoryProperties_whenBind_thenCreatesRepositoryProperties() {
        // given
        val environment = MockEnvironment()
            .withProperty("repository.stored", "true")
            .withProperty("repository.local-path", "./data/repository")

        // when
        val properties = Binder.get(environment)
            .bind("repository", RepositoryProperties::class.java)
            .get()

        // then
        assertThat(properties.stored).isTrue()
        assertThat(properties.localPath).isEqualTo("./data/repository")
        assertThat(properties.isPersistentStorageUsable()).isTrue()
        assertThat(properties.persistentStorageRoot()).isNotNull()
    }

    @Test
    @DisplayName("stored가 false이면 localPath가 있어도 persistent storage를 사용하지 않는다")
    fun givenStoredFalse_whenCheckUsable_thenReturnsFalse() {
        // given
        val properties = RepositoryProperties(stored = false, localPath = "./data/repository")

        // when
        val usable = properties.isPersistentStorageUsable()
        val storageRoot = properties.persistentStorageRoot()

        // then
        assertThat(usable).isFalse()
        assertThat(storageRoot).isNull()
    }

    @Test
    @DisplayName("localPath가 비어 있으면 persistent storage를 사용하지 않는다")
    fun givenBlankLocalPath_whenCheckUsable_thenReturnsFalse() {
        // given
        val properties = RepositoryProperties(stored = true, localPath = " ")

        // when
        val usable = properties.isPersistentStorageUsable()
        val storageRoot = properties.persistentStorageRoot()

        // then
        assertThat(usable).isFalse()
        assertThat(storageRoot).isNull()
    }

    @Test
    @DisplayName("localPath가 잘못된 path이면 persistent storage를 사용하지 않는다")
    fun givenInvalidLocalPath_whenCheckUsable_thenReturnsFalse() {
        // given
        val properties = RepositoryProperties(stored = true, localPath = "bad\u0000path")

        // when
        val usable = properties.isPersistentStorageUsable()
        val storageRoot = properties.persistentStorageRoot()

        // then
        assertThat(usable).isFalse()
        assertThat(storageRoot).isNull()
    }
}
