// 菜单栏图标：App logo 里的双箭头，去掉圆角方形底、涂黑，做成模板图。
//
// 由 scripts/gen-menubar-icon.sh 从 app_logo.svg 生成（36×36，即 18pt @2x），
// 不要手改。
//
// 直接内嵌成 base64，是为了不动 build.sh 的打包步骤 ——
// 多一个要 cp 进 Contents/Resources 的文件，就多一处能漏掉的地方。
static const char *kMenuBarIconPNGBase64 =
    "iVBORw0KGgoAAAANSUhEUgAAACQAAAAkCAYAAADhAJiYAAAC90lEQVRYw+3XXYhVVRgG4MeZ41gm"
    "TpYpMmoYFmgoEkKEXiQRRHohSqHRhd5IRJAo0k14Ef2IIBURXohCXhjURURBKiioqIT4B/0YKUqj"
    "ppRSzvhHZ+Z4sdZh1hnnnNn7eGLf+MKCs9bZ38u7vr2+9b27XWswAkuxCn/jzxbxNo3JOIUKfsMy"
    "tBcp6BHsiYIq6MVGjC9S1HR8iXIibDfmFinqQazBpUTUOaxARxaCUoP/HsIszMEUjMrAVxEOeDcm"
    "xrXHsTlyfID+vLvsECpmN/5Jdnqv4wpeyCvmUXyGGy0Uko61ecSMxfY6RDfRk3Fci88P5tiJacOJ"
    "SM/Q23g9mVdwBF/jZMzacCjjCbyLmcnaNqzH5azZmYOLyW7+wyeYkCfFeAb7hINbEc7gO0L11cMM"
    "vGSgCBBOf5reL4YhGYw2LMGZhOMsXhGqrh6ew+mYgO+qCXgYBxOiSzFjebBA6GFVjsN4NkPc+iSm"
    "Fy+2YRKmJg8dw685BT2GcZH4KyzHjxniBr+F9hI6MTpZ7MbtnIJ+wFvx9w78mzEuLap+lEsYKZyB"
    "KvKKIZT75ibiUkEVlNviYiqo3ARxsxicob6qoLQSihRUbhOM1P0MNcpQUYJGqLW5/egr3aOg8XgN"
    "1wW3mKXfpYJSj1W37PsyEj6FTVgkXBXnsSuHoNHoSua96CkNkZEsu3weHxtoMVdwNYcYMfbpZH4W"
    "fxEs5l6hwf2E2Q1I2gV/fMFAD/pF6NZ50Ilv1Db0dekDXZF0egOSMXgvprYivNqdgn3Igy5sifFV"
    "MWfwJANldyGOepiCj4SmWT1vJyJxJ+ZlEDJG8EuvqnUTZXyK37PuaAb2u9uS3hDsahZb24tbQ3D0"
    "43O1zX1YvO//Mfw92CB4+VxYHINbJeQqvsdCQ3wXNrKXVZTwIVYLd5ZIfEhwhll5buIPHMfPcd40"
    "HsCbasu9G28kIgvBfBxIRN3GVqEKC8MkoTLSj8FvhbIuDB1YKVz3FRwVPsMLxzzhUnu5VYR3AMfJ"
    "Ml71p/HoAAAAAElFTkSuQmCC"
    ;
