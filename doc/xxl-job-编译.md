本地打包： 需要在项目的根目录下执行

- mvn clean package -DskipTests -Dgpg.skip=true
- 单独构建 `xxl-job-core`: mvn clean package -pl xxl-job-core -DskipTests "-Dgpg.skip=true"


**GPG**（全称 **GNU Privacy Guard**）是一款用于加密、数字签名及密钥管理的免费开源软件，是 OpenPGP 标准的具体实现。

正常情况下，打包是不需要过滤gpg， 但gpg是需要下载对应的工具的，具体可以网上参考
