package io.github.saadfarooq.skientist.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueArgument
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.saadfarooq.skientist.Experiment

class ExperimentProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols: Sequence<KSAnnotated> = resolver.getSymbolsWithAnnotation(
            Experiment::class.qualifiedName!!
        )
        for (symbol in symbols) {
            if (symbol is KSClassDeclaration) {
                processInterface(symbol)
            }
        }
        return emptyList()
    }

    private fun processInterface(interfaceDecl: KSClassDeclaration) {
        val annotation: KSAnnotation = interfaceDecl.annotations.first {
            it.shortName.asString() == "Experiment"
        }

        val args: List<KSValueArgument> = annotation.arguments
        val experimentName: String = args.first { it.name?.asString() == "name" }.value as String
        val windowSize: Int = (args.firstOrNull { it.name?.asString() == "windowSize" }?.value as? Int) ?: 100
        val windowTimeoutMs: Long = (args.firstOrNull { it.name?.asString() == "windowTimeoutMs" }?.value as? Long) ?: 300_000

        val configTypeArg: KSValueArgument = args.first { arg ->
            arg.name?.asString() == "config"
        }
        val configType: KSType = configTypeArg.value as KSType

        val interfaceSimple = interfaceDecl.simpleName.asString()
        val prefix = interfaceSimple.replaceFirstChar { it.lowercase() }
        val interfaceType = interfaceDecl.asType(emptyList())
        val packageName: String = interfaceDecl.packageName.asString()
        val proxyName = "Experimenting$interfaceSimple"
        val sealedName = "${interfaceSimple}MethodResult"

        val methods: List<KSFunctionDeclaration> = interfaceDecl.declarations
            .filterIsInstance<KSFunctionDeclaration>()
            .toList()

        if (methods.isEmpty()) {
            logger.warn("$interfaceSimple: no abstract methods to proxy")
            return
        }

        val interfaceTypeName = interfaceType.toTypeName()
        val configTypeName = configType.toTypeName()
        val controlName = "${prefix}Control"
        val candidateName = "${prefix}Candidate"
        val configName = "${prefix}Config"
        val publishName = "${prefix}Publish"

        val sealedTypeName: ClassName = ClassName(packageName, sealedName)
        val publishLambdaType: com.squareup.kotlinpoet.TypeName =
            ClassName("kotlin", "Function1").parameterizedBy(sealedTypeName, com.squareup.kotlinpoet.UNIT)

        // 1. Generate sealed interface with per-method subtypes
        generateSealedInterface(interfaceDecl, methods, sealedName, sealedTypeName)

        // 2. Generate proxy
        val classBuilder = TypeSpec.classBuilder(proxyName)
            .addSuperinterface(interfaceTypeName)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(controlName, interfaceTypeName)
                    .addParameter(candidateName, interfaceTypeName)
                    .addParameter(publishName, publishLambdaType)
                    .build()
            )
            .addProperty(
                PropertySpec.builder(controlName, interfaceTypeName, KModifier.PRIVATE)
                    .initializer(controlName)
                    .build()
            )
            .addProperty(
                PropertySpec.builder(candidateName, interfaceTypeName, KModifier.PRIVATE)
                    .initializer(candidateName)
                    .build()
            )
            .addProperty(
                PropertySpec.builder(configName, configTypeName, KModifier.PRIVATE)
                    .initializer("%T", configTypeName)
                    .build()
            )
            .addProperty(
                PropertySpec.builder(publishName, publishLambdaType, KModifier.PRIVATE)
                    .initializer(publishName)
                    .build()
            )

        for (method in methods) {
            classBuilder.addFunction(
                generateMethod(method, experimentName, windowSize, windowTimeoutMs,
                    controlName, candidateName, configName, publishName, sealedTypeName, packageName)
            )
        }

        FileSpec.builder(packageName, proxyName)
            .addImport("io.github.saadfarooq.skientist",
                "enabled", "compareWith", "publish", "control", "candidate", "window", "methodName")
            .addType(classBuilder.build())
            .build()
            .writeTo(codeGenerator, Dependencies(false, interfaceDecl.containingFile!!))
    }

    // ── Sealed interface generation ────────────────────

    private fun generateSealedInterface(
        interfaceDecl: KSClassDeclaration,
        methods: List<KSFunctionDeclaration>,
        sealedName: String,
        sealedTypeName: ClassName,
    ) {
        val sealedBuilder = TypeSpec.interfaceBuilder(sealedName)
            .addModifiers(KModifier.SEALED)

        for (method in methods) {
            val methodName = method.simpleName.asString()
            val subtypeName = methodName.replaceFirstChar { it.uppercase() }
            val returnType: KSType = method.returnType!!.resolve()
            val isFlow = returnType.declaration.qualifiedName?.asString() == "kotlinx.coroutines.flow.Flow"
            val innerTypeName = if (isFlow) {
                val typeArgs = returnType.arguments
                if (typeArgs.isNotEmpty()) typeArgs[0].type!!.resolve().toTypeName()
                else com.squareup.kotlinpoet.STAR
            } else returnType.toTypeName()

            val resultType = if (isFlow) {
                ClassName("io.github.saadfarooq.skientist", "FlowExperimentResult")
                    .parameterizedBy(innerTypeName)
            } else {
                ClassName("io.github.saadfarooq.skientist", "ExperimentResult")
                    .parameterizedBy(innerTypeName)
            }

            val subtype = TypeSpec.classBuilder(subtypeName)
                .addSuperinterface(sealedTypeName)
                .addModifiers(KModifier.DATA)
                .primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameter("result", resultType)
                        .build()
                )
                .addProperty(
                    PropertySpec.builder("result", resultType)
                        .initializer("result")
                        .build()
                )
                .build()

            sealedBuilder.addType(subtype)
        }

        FileSpec.builder(interfaceDecl.packageName.asString(), sealedName)
            .addType(sealedBuilder.build())
            .build()
            .writeTo(codeGenerator, Dependencies(false, interfaceDecl.containingFile!!))
    }

    // ── Method generation ──────────────────────────────

    private fun generateMethod(
        method: KSFunctionDeclaration,
        experimentName: String,
        windowSize: Int,
        windowTimeoutMs: Long,
        controlName: String,
        candidateName: String,
        configName: String,
        publishName: String,
        sealedTypeName: ClassName,
        packageName: String,
    ): FunSpec {
        val methodName: String = method.simpleName.asString()
        val returnType: KSType = method.returnType!!.resolve()
        val returnTypeName = returnType.toTypeName()

        val isFlow = returnType.declaration.qualifiedName?.asString() == "kotlinx.coroutines.flow.Flow"
        val innerTypeName = if (isFlow) {
            val typeArgs = returnType.arguments
            if (typeArgs.isNotEmpty()) typeArgs[0].type!!.resolve().toTypeName()
            else com.squareup.kotlinpoet.STAR
        } else returnTypeName

        val paramDefs: List<Pair<String, com.squareup.kotlinpoet.TypeName>> = method.parameters.map { param ->
            param.name!!.asString() to param.type.resolve().toTypeName()
        }
        val paramNames: String = paramDefs.joinToString(", ") { it.first }

        val funBuilder = FunSpec.builder(methodName)
            .addModifiers(KModifier.OVERRIDE)
            .returns(returnTypeName)

        if (method.modifiers.contains(Modifier.SUSPEND)) {
            funBuilder.addModifiers(KModifier.SUSPEND)
        }
        for ((name, type) in paramDefs) {
            funBuilder.addParameter(name, type)
        }

        val experimentFunName = if (isFlow) "experimentFlow" else "experiment"
        val experimentFun = MemberName("io.github.saadfarooq.skientist", experimentFunName)
        val subtypeSimpleName = methodName.replaceFirstChar { it.uppercase() }
        val subtypeClass = ClassName(packageName, sealedTypeName.simpleName, subtypeSimpleName)

        @Suppress("MaxLineLength")
        if (isFlow) {
            funBuilder.addCode(
                """
                |return %M<%T>(%S) {
                |  methodName(%S)
                |  window(size = $windowSize, timeoutMs = $windowTimeoutMs)
                |  enabled { ${configName}.enabled() }
                |  compareWith { a, b -> ${configName}.compareWith(a, b) }
                |  publish { ${publishName}(%T(it)) }
                |  control { ${controlName}.%L(%L) }
                |  candidate { ${candidateName}.%L(%L) }
                |}
                """.trimMargin(),
                experimentFun, innerTypeName, experimentName, methodName,
                subtypeClass,
                methodName, paramNames,
                methodName, paramNames,
            )
        } else {
            funBuilder.addCode(
                """
                |return %M<%T>(%S) {
                |  methodName(%S)
                |  enabled { ${configName}.enabled() }
                |  compareWith { a, b -> ${configName}.compareWith(a, b) }
                |  publish { ${publishName}(%T(it)) }
                |  control { ${controlName}.%L(%L) }
                |  candidate { ${candidateName}.%L(%L) }
                |}
                """.trimMargin(),
                experimentFun, innerTypeName, experimentName, methodName,
                subtypeClass,
                methodName, paramNames,
                methodName, paramNames,
            )
        }

        return funBuilder.build()
    }
}
