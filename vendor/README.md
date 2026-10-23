# RayNeo SDK libraries (not included)

Put the two Android libraries of the **RayNeo OpenXR ARDK 1.1.2** package here:

```
vendor/rayneo-sdk-1.1.2/RayNeoSDK-client.aar
vendor/rayneo-sdk-1.1.2/RayNeoOpenXRLoader.aar
```

Download the package from RayNeo's developer documentation
(<https://rayneo-en.gitbook.io/rayneo-devdoc/x-series/unity-sdk/ardk-download>) and take both files from
`OXR/Runtime/Plugins/Android/` inside it. Only these two Android libraries are used; Unity is not needed.

The build accepts only these exact files (SHA-256):

| File | SHA-256 |
| --- | --- |
| `RayNeoSDK-client.aar` | `7deab5b1754065106065a5eea1fdab9dbf58f75c7226c8a60bef737141f7b28f` |
| `RayNeoOpenXRLoader.aar` | `36c36d54603570fff2cc069f6c37c5398829990a082bdf204a12961febf76fab` |

Another directory can be used with `rayneoSdkDir=<path>` in `gradle.properties` (relative to the root
project) or `-PrayneoSdkDir=<path>`.

`vendor/rayneo-sdk-*/` is ignored by git. **Never commit these files or publish APKs containing them
in this repository's releases**: they are RayNeo's, under RayNeo's terms (package licence string
"OpenXR RAYNEO"), which grant no redistribution. Your own app packages them as you are permitted by RayNeo.
