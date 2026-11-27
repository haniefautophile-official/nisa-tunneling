# NISA NEXUS - aturan R8 (release). Ditulis dari audit refleksi, bukan -keep ** lebar.
# Semua aturan di sini punya alasan; jangan tambah -keep class ** {*;}.

# --- Atribut yang dibutuhkan Gson (generic type) & anotasi ---
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
# Stack trace tetap bisa dibaca lewat mapping.txt
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Gson: DTO dipetakan lewat NAMA FIELD (tanpa @SerializedName), jadi nama field wajib utuh.
# Ini juga format penyimpanan MMKV profil/subscription -> kompatibilitas update lama.
-keep class com.v2ray.ang.dto.** { *; }
-keep class com.v2ray.ang.enums.** { *; }
# Kelas privat/nested yang diserialisasi Gson di luar paket dto
-keep class com.v2ray.ang.handler.NisaShare$Payload { *; }
-keep class com.v2ray.ang.handler.NisaShare$Entry { *; }
-keep class com.v2ray.ang.handler.NisaShare$Envelope { *; }
-keep class com.v2ray.ang.fmt.NisaExtra$Data { *; }
# TypeToken anonim (JsonUtil.toJsonPretty)
-keep class * extends com.google.gson.reflect.TypeToken
-dontwarn sun.misc.Unsafe

# --- Native core (gomobile): dipanggil dari kode Go lewat nama kelas/metode ---
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-keepclassmembers class * implements libv2ray.CoreCallbackHandler { *; }
-keepclassmembers class * implements libv2ray.ProcessFinder { *; }

# --- JNI hev-socks5-tunnel: method native dipanggil dari .so ---
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.v2ray.ang.service.TProxyService { native <methods>; }

# --- MMKV: JNI memanggil balik kelas/handler ---
-keep class com.tencent.mmkv.** { *; }

# --- Serializable (dto dikirim lewat Intent/MessageHelper) ---
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# --- Peringatan opsional dari library ---
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
