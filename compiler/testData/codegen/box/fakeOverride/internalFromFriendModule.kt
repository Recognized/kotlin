// IGNORE_BACKEND: WASM
// IGNORE_CODEGEN_WITH_IR_FAKE_OVERRIDE_GENERATION: KT-61384

// MODULE: lib
// FILE: l.kt

open class Base {
    internal fun o() = "O"
}

open internal class In {
    fun k() = "K"
}

// MODULE: main()(lib)
// FILE: m.kt

class Derived: Base()
internal class IDerived: In()

fun box(): String = Derived().o() + IDerived().k()
