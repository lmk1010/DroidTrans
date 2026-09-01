package license

// 签发方的 Ed25519 公钥。
//
// 私钥在授权服务那边（licensing/.env），只有它能签出有效许可证。
// 这里放的是公钥，泄漏无所谓 —— 它只能验证，不能签发。
//
// 换这把钥匙会让所有已售出的许可证一起失效，除非同时保留旧公钥做过渡。
const publicKeyPEM = `-----BEGIN PUBLIC KEY-----
MCowBQYDK2VwAyEA1DoaCefyZVtNEp7mzFstWOPezLYPU6LPuUkIv1R5hqo=
-----END PUBLIC KEY-----`
