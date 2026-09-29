package com.ratatoskr.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ErrorDetailTest {

    @Test
    fun string_detail_from_http_exception() {
        assertEquals(
            "Incorrect username or master password",
            parseErrorDetail("""{"detail":"Incorrect username or master password"}"""),
        )
    }

    @Test
    fun list_detail_from_validation_error() {
        val body = """{"detail":[{"type":"string_too_short","loc":["body","master_password"],""" +
            """"msg":"String should have at least 8 characters","input":"x","ctx":{"min_length":8}}]}"""
        assertEquals("master_password: String should have at least 8 characters", parseErrorDetail(body))
    }

    @Test
    fun missing_detail() {
        assertNull(parseErrorDetail("""{"error":"nope"}"""))
    }
}
