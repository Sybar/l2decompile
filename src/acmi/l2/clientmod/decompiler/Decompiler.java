/*
 * Copyright (c) 2016 acmi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package acmi.l2.clientmod.decompiler;

import acmi.l2.clientmod.io.UnrealPackageReadOnly;
import acmi.l2.clientmod.unreal.core.*;
import acmi.l2.clientmod.unreal.core.Class;
import acmi.l2.clientmod.unreal.core.Enum;
import acmi.l2.clientmod.unreal.core.Function;
import acmi.l2.clientmod.unreal.core.Object;
import acmi.l2.clientmod.unreal.objectfactory.ObjectFactory;
import javafx.util.Pair;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static acmi.l2.clientmod.decompiler.Util.*;

public class Decompiler {
    static Object instantiate(UnrealPackageReadOnly.ExportEntry entry, ObjectFactory objectFactory) {
        String objClass = entry.getObjectClass() == null ? "Core.Class" : entry.getObjectClass().getObjectFullName();
        if (objClass.equals("Core.Class") ||
                objClass.equals("Core.State") ||
                objClass.equals("Core.Function") ||
                objClass.equals("Core.Struct")) {
            return objectFactory.getClassLoader().getStruct(entry.getObjectFullName());
        } else {
            return objectFactory.apply(entry);
        }
    }

    public static CharSequence decompile(Class clazz, ObjectFactory objectFactory, int indent) {
        StringBuilder sb = new StringBuilder();

        String name = clazz.getEntry().getObjectName().getName();
        String superName = clazz.getEntry().getObjectSuperClass() != null ?
                clazz.getEntry().getObjectSuperClass().getObjectName().getName() : null;

        sb.append("class ").append(name);
        if (superName != null)
            sb.append(" extends ").append(superName);
        //TODO flags
        sb.append(";");

        if (clazz.getChild() != null) {
            sb.append(newLine());
            sb.append(newLine(indent)).append(decompileFields(clazz, objectFactory, indent));
        }

        //TODO defaultproperties

        return sb;
    }

    public static CharSequence decompileFields(Struct struct, ObjectFactory objectFactory, int indent) {
        Stream.Builder<CharSequence> fields = Stream.builder();

        for (Field field : (Iterable<Field>) () -> new ChildIterator(struct, objectFactory)) {
            if (field instanceof Const) {
                fields.add(decompileConst((Const) field, objectFactory, indent) + ";");
            } else if (field instanceof Enum) {
                if (!struct.getClass().equals(Struct.class))
                    fields.add(decompileEnum((Enum) field, objectFactory, indent) + ";");
            } else if (field instanceof Property) {
                if (field instanceof DelegateProperty)
                    continue;

                fields.add(decompileProperty((Property) field, struct, objectFactory, indent) + ";");
            } else if (field instanceof State) {
                fields.add(decompileState((State) field, objectFactory, indent));
            } else if (field instanceof Function) {
                fields.add(decompileFunction((Function) field, objectFactory, indent));
            } else if (field instanceof Struct) {
                fields.add(decompileStruct((Struct) field, objectFactory, indent) + ";");
            } else {
                fields.add(field.toString());
            }
        }

        return fields.build().collect(Collectors.joining(newLine(indent)));
    }

    public static CharSequence decompileConst(Const c, ObjectFactory objectFactory, int indent) {
        StringBuilder sb = new StringBuilder();

        sb.append("const ")
                .append(c.getEntry().getObjectName().getName())
                .append(" =")
                .append(c.constant);

        return sb;
    }

    public static CharSequence decompileEnum(Enum e, ObjectFactory objectFactory, int indent) {
        StringBuilder sb = new StringBuilder();

        sb.append("enum ").append(e.getEntry().getObjectName().getName())
                .append(newLine(indent)).append("{")
                .append(newLine(indent + 1)).append(e.getValues().stream().collect(Collectors.joining("," + newLine(indent + 1))))
                .append(newLine(indent)).append("}");

        return sb;
    }

    public static CharSequence decompileProperty(Property property, Struct parent, ObjectFactory objectFactory, int indent) {
        StringBuilder sb = new StringBuilder();

        Collection<Property.CPF> propertyFlags = property.getPropertyFlags();
        Collection<UnrealPackageReadOnly.ObjectFlag> objectFlags = UnrealPackageReadOnly.ObjectFlag.getFlags(property.getEntry().getObjectFlags());

        sb.append("var");
        CharSequence type = getType(property, objectFactory, true);
        if (parent.getClass().equals(Struct.class)) {
            if (property instanceof ByteProperty &&
                    ((ByteProperty) property).enumType != 0) {
                UnrealPackageReadOnly.Entry enumLocalEntry = ((ByteProperty) property).getEnumType();
                UnrealPackageReadOnly.ExportEntry enumEntry = objectFactory.getClassLoader()
                        .getExportEntry(enumLocalEntry.getObjectFullName(), e -> e.getObjectClass() != null && e.getObjectClass().getObjectFullName().equalsIgnoreCase("Core.Enum"));
                Enum en = (Enum) objectFactory.apply(enumEntry);
                type = decompileEnum(en, objectFactory, indent);
            }
            //FIXME array<enum>
        }
        sb.append(" ").append(type).append(" ");
        sb.append(property.getEntry().getObjectName().getName());
        if (property.arrayDimension > 1)
            sb.append("[").append(property.arrayDimension).append("]");

        return sb;
    }

    private static final List<Pair<Predicate<Property>, java.util.function.Function<Property, String>>> MODIFIERS = Arrays.asList(
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.Edit), p -> "(" + (p.getEntry().getObjectPackage().getObjectName().getName().equalsIgnoreCase(p.getCategory()) ? "" : p.getCategory()) + ")"),
            new Pair<>(p -> UnrealPackageReadOnly.ObjectFlag.getFlags(p.getEntry().getObjectFlags()).contains(UnrealPackageReadOnly.ObjectFlag.Private), p -> "private"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.Const), p -> "const"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.Input), p -> "input"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.ExportObject), p -> "export"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.OptionalParm), p -> "optional"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.OutParm), p -> "out"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.SkipParm), p -> "skip"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.CoerceParm), p -> "coerce"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.Native), p -> "native"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.Transient), p -> "transient"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.Config), p -> p.getPropertyFlags().contains(Property.CPF.GlobalConfig) ? null : "config"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.Localized), p -> "localized"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.Travel), p -> "travel"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.EditConst), p -> "editconst"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.GlobalConfig), p -> "globalconfig"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.EditInline), p -> p.getPropertyFlags().contains(Property.CPF.EditInlineUse) ? null : "editinline"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.EdFindable), p -> "edfindable"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.EditInlineUse), p -> "editinlineuse"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.Deprecated), p -> "deprecated"),
            new Pair<>(p -> p.getPropertyFlags().contains(Property.CPF.EditInlineNotify), p -> "editinlinenotify")
    );

    public static CharSequence getType(Property property, ObjectFactory objectFactory, boolean includeModifiers) {
        StringBuilder sb = new StringBuilder();

        if (includeModifiers) {
            MODIFIERS.stream()
                    .filter(p -> p.getKey().test(property))
                    .map(p -> p.getValue().apply(property))
                    .forEach(m -> sb.append(m).append(" "));
        }
        if (property instanceof ByteProperty) {
            if (((ByteProperty) property).enumType != 0) {
                UnrealPackageReadOnly.Entry enumLocalEntry = ((ByteProperty) property).getEnumType();
                sb.append(enumLocalEntry.getObjectName().getName());
            } else {
                sb.append("byte");
            }
        } else if (property instanceof IntProperty) {
            sb.append("int");
        } else if (property instanceof BoolProperty) {
            sb.append("bool");
        } else if (property instanceof FloatProperty) {
            sb.append("float");
        } else if (property instanceof ObjectProperty) {
            sb.append(((ObjectProperty) property).getObjectType().getObjectName().getName());
        } else if (property instanceof NameProperty) {
            sb.append("name");
        } else if (property instanceof ArrayProperty) {
            ArrayProperty arrayProperty = (ArrayProperty) property;
            Property innerProperty = (Property) objectFactory.apply(objectFactory.getClassLoader().getExportEntry(arrayProperty.getInner().getObjectFullName(), e -> true));
            sb.append("array<").append(getType(innerProperty, objectFactory, false)).append(">");
        } else if (property instanceof StructProperty) {
            sb.append(((StructProperty) property).getStructType().getObjectName().getName());
        } else if (property instanceof StrProperty) {
            sb.append("string");
        }

        return sb;
    }

    public static CharSequence decompileStruct(Struct struct, ObjectFactory objectFactory, int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append("struct ").append(struct.getEntry().getObjectName().getName());
        sb.append(newLine(indent)).append("{");
        sb.append(newLine(indent + 1)).append(decompileFields(struct, objectFactory, indent + 1));
        sb.append(newLine(indent)).append("}");
        return sb;
    }

    public static CharSequence decompileFunction(Function function, ObjectFactory objectFactory, int indent) {
        StringBuilder sb = new StringBuilder();

        sb.append("//function_").append(function.getFriendlyName()); //TODO

        return sb;
    }

    public static CharSequence decompileState(State state, ObjectFactory objectFactory, int indent) {
        StringBuilder sb = new StringBuilder();

        sb.append("state ");
        sb.append(state.getEntry().getObjectName().getName());
        if (state.getEntry().getObjectSuperClass() != null) {
            sb.append(" extends ").append(state.getEntry().getObjectSuperClass().getObjectName().getName());
        }
        sb.append(newLine(indent)).append("{");
        sb.append(newLine(indent + 1)).append(decompileFields(state, objectFactory, indent + 1));
        sb.append(newLine(indent)).append("}");

        return sb;
    }
}
