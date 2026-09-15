package com.poc.a11y.atf;
import java.nio.file.Path;
public class ApkVersionProbe {
  public static void main(String[] args) {
    Path harness = Path.of("atf-harness/app/build/outputs/apk/debug/app-debug.apk");
    Path testApk = Path.of("atf-harness/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk");
    PackageIdentity h = ApkManifestReader.read(harness);
    PackageIdentity t = ApkManifestReader.read(testApk);
    System.out.println("harness=" + h);
    System.out.println("test=" + t);
    long now = System.currentTimeMillis();
    PackageIdentity installedSame = PackageIdentity.fromDumpsys("Package [x]:\n    versionCode=1 minSdk=26\n    versionName=1.0\n    lastUpdateTime=2099-01-01 00:00:00");
    PackageIdentity installedOld = PackageIdentity.fromDumpsys("Package [x]:\n    versionCode=1 minSdk=26\n    versionName=1.0\n    lastUpdateTime=2020-01-01 00:00:00");
    PackageIdentity installedV2 = PackageIdentity.fromDumpsys("Package [x]:\n    versionCode=2 minSdk=26\n    versionName=2.0\n    lastUpdateTime=2020-01-01 00:00:00");
    System.out.println("same version recent install skip=" + !PackageIdentity.needsInstall(installedSame, h, now));
    System.out.println("same version but apk newer=" + PackageIdentity.needsInstall(installedOld, h, now));
    System.out.println("different version=" + PackageIdentity.needsInstall(installedV2, h, now));
    System.out.println("missing=" + PackageIdentity.needsInstall(null, h, now));
    System.out.println("test apk no version, recent install skip=" + !PackageIdentity.needsInstall(installedSame, t, now));
    System.out.println("test apk no version, old install=" + PackageIdentity.needsInstall(installedOld, t, now));
  }
}
