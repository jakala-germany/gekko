plugins { id("com.ncorti.ktfmt.gradle") }

ktfmt {
    kotlinLangStyle()
    maxWidth.set(120)
}
