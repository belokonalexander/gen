package com.belax.timerapp

import com.belax.generator.GenericSolver
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import org.junit.Test
import java.util.*


/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        val string = "java.lang.Set<java.lang.Integer>"
        //getClassName("kotlin.Pair")
        val genericSolver = GenericSolver()

        println(genericSolver.run(string))
    }



}

