package org.openkaryon.karyoforge.uitest

internal fun notebookFixtureJson(count: Int, firstSource: String): String {
    val cells = (0 until count).joinToString(",") { index ->
        val source = if (index == 0) firstSource else "value = $index"
        val outputs = if (index == 0) """[{"output_type":"stream","name":"stdout","text":"saved result only"}]""" else "[]"
        """{"id":"cell-$index","cell_type":"code","metadata":{},"source":${notebookFixtureString(source)},"outputs":$outputs,"execution_count":1}"""
    }
    return """{"nbformat":4,"nbformat_minor":5,"metadata":{"custom":{"preserved":true}},"cells":[$cells]}""" + "\n"
}

private fun notebookFixtureString(value: String): String = buildString {
    append('"')
    for (character in value) when (character) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (character.code < 32) append("\\u%04x".format(character.code)) else append(character)
    }
    append('"')
}
