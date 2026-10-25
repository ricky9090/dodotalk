package org.dodotalk.uilts;

import org.dodotalk.SqueakConfig;
import org.dodotalk.core.SqueakObject;
import org.dodotalk.core.SqueakVM;

import java.lang.ref.WeakReference;

public class ObjectUtils {

    public static final String OBJ_NULL = "<Null Object>";
    public static final String OBJ_FLOAT = "a Float";
    public static final String OBJ_POINT = "a Point";
    public static final String OBJ_RECTANGLE = "a Rectangle";

    /**
     * Helper method for converting an object to string
     */
    public static String toString(Object target) {
        String res = "";

        if (target == null) {
            return "<Null Object>";
        }

        if (target instanceof Integer) {
            return "" + target;
        }

        if (OBJ_FLOAT.equals(target.toString())) {
            SqueakObject aFloat = (SqueakObject) target;
            if (aFloat.getBits() != null) {
                return aFloat.getBits().toString();
            } else {
                return "<Error Float Number>";
            }
        }

        if (OBJ_POINT.equals(target.toString())) {
            SqueakObject point = (SqueakObject) target;

            Object[] pointers = point.getPointers();
            if (pointers != null && pointers.length == 2) {
                Object a = pointers[0];
                Object b = pointers[1];

                return ObjectUtils.toString(a) + "@" + ObjectUtils.toString(b);
            } else {
                return "<Error Point>";
            }
        }

        if (OBJ_RECTANGLE.equals(target.toString())) {
            // dump a Rectangle
            SqueakObject rectangle = (SqueakObject) target;

            Object[] pointers = rectangle.getPointers();
            if (pointers != null && pointers.length == 2) {
                Object a = pointers[0];
                Object b = pointers[1];

                return ObjectUtils.toString(a) + "=>" + ObjectUtils.toString(b);
            } else {
                return "<Error Rectangle>";
            }
        }

        res = target.toString();
        return res;
    }

    /**
     * 打印Image映像中的所有对象类型，调试用
     */
    public static void dumpObjOfImage() {
        if (!SqueakConfig.ENABLE_DEBUG_LOGGING) {
            return;
        }

        int length = SqueakVM.objectMemory.getObjectTableLength();
        for (int i = 0; i < length; i++) {
            WeakReference<Object> objectWeakReference = SqueakVM.objectMemory.getObjectAt(i);
            if (objectWeakReference == null) {
                continue;
            }
            Object real = objectWeakReference.get();
            if (real == null) {
                continue;
            }
            SqueakObject realObj = null;
            if (real instanceof SqueakObject) {
                realObj = (SqueakObject) real;
            }
            if (realObj == null) {
                continue;
            }

            String objType = realObj.toString();
            if (OBJ_POINT.equals(objType)) {
                // dump a Point
                SqueakLogger.log_D("[" + i + "] create Point: " + ObjectUtils.toString(realObj));

            } else if (OBJ_RECTANGLE.equals(objType)) {
                // dump a Rectangle
                SqueakLogger.log_D("[" + i + "] create Rectangle: " + ObjectUtils.toString(realObj));

            } else if (OBJ_FLOAT.equals(objType)) {
                // dump a Float
                SqueakLogger.log_D("[" + i + "] create Float: " + ObjectUtils.toString(realObj));

            } else {
                SqueakLogger.log_D("[" + i + "] " + describeObject(realObj));
            }
        }
    }

    /**
     * Produce a short, console-safe description for image object dumps. Byte
     * objects (bitmaps, compiled methods, strings with binary data, ...) are
     * summarized instead of being converted to a raw Java String.
     */
    private static String describeObject(SqueakObject obj) {
        Object bits = obj.getBits();
        if (bits instanceof byte[] && obj.getPointers() == null) {
            byte[] bytes = (byte[]) bits;
            return "byte object format=" + obj.getFormat()
                    + ", size=" + bytes.length
                    + hexPrefix(bytes);
        }
        return ObjectUtils.toString(obj);
    }

    private static String hexPrefix(byte[] bytes) {
        int count = Math.min(bytes.length, 32);
        StringBuilder result = new StringBuilder(" hex=");
        for (int i = 0; i < count; i++) {
            if (i > 0 && (i % 16) == 0) {
                result.append(' ');
            }
            int value = bytes[i] & 0xFF;
            result.append(HEX[(value >> 4) & 0xF]);
            result.append(HEX[value & 0xF]);
        }
        if (bytes.length > count) {
            result.append("...");
        }
        return result.toString();
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
}
