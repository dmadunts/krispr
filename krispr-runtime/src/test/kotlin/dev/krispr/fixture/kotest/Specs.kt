package dev.krispr.fixture.kotest

import io.kotest.core.spec.Spec

abstract class BaseSpec : Spec()

class CalculatorSpec : BaseSpec()

class SlowSpec : Spec()

class Outer {
    class NestedSpec : Spec()
}

class NotASpec
