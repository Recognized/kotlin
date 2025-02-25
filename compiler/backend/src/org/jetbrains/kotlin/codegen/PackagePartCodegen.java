/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.codegen;

import com.intellij.util.ArrayUtil;
import kotlin.Pair;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.backend.common.CodegenUtil;
import org.jetbrains.kotlin.codegen.context.FieldOwnerContext;
import org.jetbrains.kotlin.codegen.serialization.JvmSerializerExtension;
import org.jetbrains.kotlin.codegen.state.GenerationState;
import org.jetbrains.kotlin.descriptors.MemberDescriptor;
import org.jetbrains.kotlin.descriptors.annotations.Annotated;
import org.jetbrains.kotlin.descriptors.annotations.AnnotatedImpl;
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor;
import org.jetbrains.kotlin.descriptors.annotations.Annotations;
import org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader;
import org.jetbrains.kotlin.metadata.ProtoBuf;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtDeclaration;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.resolve.BindingContext;
import org.jetbrains.kotlin.resolve.jvm.JvmClassName;
import org.jetbrains.kotlin.serialization.DescriptorSerializer;
import org.jetbrains.org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.jetbrains.kotlin.codegen.DescriptorAsmUtil.writeAnnotationData;
import static org.jetbrains.kotlin.load.java.JvmAnnotationNames.METADATA_PACKAGE_NAME_FIELD_NAME;
import static org.jetbrains.kotlin.name.JvmStandardClassIds.JVM_SYNTHETIC_ANNOTATION_FQ_NAME;
import static org.jetbrains.org.objectweb.asm.Opcodes.*;

public class PackagePartCodegen extends MemberCodegen<KtFile> {
    private final Type packagePartType;

    public PackagePartCodegen(
            @NotNull ClassBuilder v,
            @NotNull KtFile file,
            @NotNull Type packagePartType,
            @NotNull FieldOwnerContext context,
            @NotNull GenerationState state
    ) {
        super(state, null, context, file, v);
        this.packagePartType = packagePartType;
    }

    @Override
    protected void generateDeclaration() {
        boolean isSynthetic = false;
        List<AnnotationDescriptor> fileAnnotationDescriptors = new ArrayList<>();
        for (KtAnnotationEntry annotationEntry : element.getAnnotationEntries()) {
            AnnotationDescriptor annotationDescriptor = state.getBindingContext().get(BindingContext.ANNOTATION, annotationEntry);
            if (annotationDescriptor != null) {
                fileAnnotationDescriptors.add(annotationDescriptor);
                if (Objects.equals(annotationDescriptor.getFqName(), JVM_SYNTHETIC_ANNOTATION_FQ_NAME)) {
                    isSynthetic = true;
                }
            }
        }

        v.defineClass(element, state.getConfig().getClassFileVersion(),
                      ACC_PUBLIC | ACC_FINAL | ACC_SUPER | (isSynthetic ? ACC_SYNTHETIC : 0),
                      packagePartType.getInternalName(),
                      null,
                      "java/lang/Object",
                      ArrayUtil.EMPTY_STRING_ARRAY
        );
        v.visitSource(element.getName(), null);

        generatePropertyMetadataArrayFieldIfNeeded(packagePartType);

        Annotated annotatedFile = new AnnotatedImpl(Annotations.Companion.create(fileAnnotationDescriptors));
        AnnotationCodegen.forClass(v.getVisitor(), this, state).genAnnotations(annotatedFile, null, null);
    }

    @Override
    protected void generateBody() {
        for (KtDeclaration declaration : CodegenUtil.getMemberDeclarationsToGenerate(element)) {
            genSimpleMember(declaration);
        }

        if (state.getClassBuilderMode().generateBodies) {
            generateInitializers(this::createOrGetClInitCodegen);
        }
    }

    @Override
    protected void generateKotlinMetadataAnnotation() {
        Pair<DescriptorSerializer, ProtoBuf.Package> serializedPart = serializePackagePartMembers(this, packagePartType);

        WriteAnnotationUtilKt.writeKotlinMetadata(v, state, KotlinClassHeader.Kind.FILE_FACADE, false, 0, av -> {
            writeAnnotationData(av, serializedPart.getFirst(), serializedPart.getSecond());

            FqName kotlinPackageFqName = element.getPackageFqName();
            if (!kotlinPackageFqName.equals(JvmClassName.byInternalName(packagePartType.getInternalName()).getPackageFqName())) {
                av.visit(METADATA_PACKAGE_NAME_FIELD_NAME, kotlinPackageFqName.asString());
            }

            return Unit.INSTANCE;
        });
    }

    @NotNull
    protected static Pair<DescriptorSerializer, ProtoBuf.Package> serializePackagePartMembers(
            @NotNull MemberCodegen<? extends KtFile> codegen,
            @NotNull Type packagePartType
    ) {
        List<MemberDescriptor> members = CodegenUtil.getMemberDescriptorsToGenerate(codegen.element, codegen.bindingContext);

        JvmSerializerExtension extension = new JvmSerializerExtension(codegen.v.getSerializationBindings(), codegen.state);
        DescriptorSerializer serializer = DescriptorSerializer.createTopLevel(
                extension, codegen.state.getLanguageVersionSettings(), null);
        ProtoBuf.Package.Builder builder = serializer.packagePartProto(codegen.element.getPackageFqName(), members);
        extension.serializeJvmPackage(builder, packagePartType);

        return new Pair<>(serializer, builder.build());
    }

    @Override
    protected void generateSyntheticPartsAfterBody() {
        generateSyntheticAccessors();
    }
}
