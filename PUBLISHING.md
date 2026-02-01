# 发布指南 / Publishing Guide

本文说明如何将本 Maven 插件发布出去，让其他人通过 Maven Central 使用。

---

## 重要：Gradle 插件 vs Maven 插件，以及 groupId

### Gradle 插件是怎么做的？

现有 [Checker Framework Gradle Plugin](https://github.com/kelloggm/checkerframework-gradle-plugin) 的做法是：

- **发布目标不是 Maven Central**，而是 **Gradle Plugin Portal**（[plugins.gradle.org](https://plugins.gradle.org)）。
- 使用 `com.gradle.plugin-publish` 插件，通过 `./gradlew publishPlugins` 发布。
- 虽然 `build.gradle` 里写了 `group = "org.checkerframework"`，但 Gradle Plugin Portal 有自己的 **Plugin ID** 体系（例如 `org.checkerframework`），和 Maven 的 groupId 不是同一套验证。
- 发布凭证由 **Checker Framework 维护者** 保管；RELEASE.md 里写的是「联系维护者获取凭证」。也就是说，Gradle 插件是由官方/维护者账号发布到 Plugin Portal 的。

所以：**Gradle 插件没有走 Maven Central，因此不涉及 Maven Central 的 namespace（groupId）验证。**

### 我能在 Maven Central 里用 `org.checkerframework` 吗？

- **Maven Central** 要求每个 **groupId**（namespace）都要在 [Central Publisher Portal](https://central.sonatype.com/) 上完成 **Namespace 验证**，且只有通过验证的账号才能向该 namespace 发布。
- `org.checkerframework` 几乎可以确定已由 **Checker Framework / typetools** 官方验证。若你的账号没有获得该 namespace 的发布权限，用 `groupId=org.checkerframework` 发布会被 Portal **拒绝**（验证不通过）。
- 因此：**在未被 Checker Framework 接纳为维护者/受权发布者之前，你不能在 Maven Central 上使用 `org.checkerframework` 作为 groupId。**

### 建议做法（在项目被官方接纳之前）

- 使用 **你自己的 groupId** 发布到 Maven Central，例如：**`io.github.你的GitHub用户名`**（仓库在 GitHub 时，可在 Portal 里通过关联 GitHub 仓库验证该 namespace）。
- 在 `pom.xml` 里把 `<groupId>` 改为该值，并同步修改 `<url>`、`<scm>`、`<developers>` 等为你的仓库/信息。
- 等将来项目被 Checker Framework 官方接纳、由官方用 `org.checkerframework` 发布时，再在文档里说明「官方版本」与「个人维护版本」的坐标区别即可。

下面「一、发布到 Maven Central」按你**使用个人 groupId** 的情况编写；若你已获授权使用 `org.checkerframework`，只需在 Portal 完成该 namespace 的验证即可。

---

## 一、发布到 Maven Central（推荐）

发布到 [Maven Central](https://central.sonatype.com/) 后，用户只需在 `pom.xml` 里声明 `groupId`、`artifactId`、`version`，无需配置额外仓库即可使用。

### 1. 前置准备

#### 1.1 注册并验证 Namespace

- 打开 [Central Publisher Portal](https://central.sonatype.com/)
- 用 GitHub 或邮箱注册账号
- 创建/验证 **Namespace**（即 Maven 的 `groupId`）  
  - 当前项目的 `groupId` 是 `org.checkerframework`  
  - 若这是官方/受权项目，需在 Portal 中验证对该 namespace 的所有权  
  - 若是个人或社区项目，可考虑改用例如 `io.github.你的用户名` 作为 `groupId`，并在 Portal 中验证（通常通过关联 GitHub 仓库完成）

#### 1.2 创建 GPG 密钥（用于签名）

Maven Central 要求所有构件必须用 GPG 签名。

```bash
# 生成密钥（按提示填写姓名、邮箱等）
gpg --full-generate-key

# 查看公钥 ID（列出密钥，记下 pub 行中的 ID，形如 ABC12345）
gpg --list-keys --keyid-format short

# 将公钥发布到 keyserver，供 Central 校验
gpg --keyserver keyserver.ubuntu.com --send-keys 你的公钥ID
```

#### 1.3 创建 Central 发布用 Token

- 在 [Central Publisher Portal](https://central.sonatype.com/) 中进入 **Generate User Token**
- 生成 Token 后，会得到 **username** 和 **password**（即 token），后面配置 Maven 时要用

### 2. 配置 Maven

在 **本机** 的 `~/.m2/settings.xml` 里添加（没有该文件可新建）：

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>这里填 Token 的 username</username>
      <password>这里填 Token 的 password</password>
    </server>
  </servers>
</settings>
```

`<id>central</id>` 必须与 `pom.xml` 里 `central-publishing-maven-plugin` 的 `publishingServerId` 一致（当前已设为 `central`）。

### 3. 发布步骤

在项目根目录执行：

```bash
# 1. 确保版本号不是 -SNAPSHOT（Central 只接受正式版）
#    在 pom.xml 中确认 <version> 为例如 1.0.0

# 2. 打包、生成源码/文档 JAR、签名并部署
mvn clean deploy
```

执行过程中会：

- 打包主 JAR、sources JAR、javadoc JAR
- 使用 GPG 对上述构件签名
- 将构件上传到 Central Publisher Portal

若未配置 GPG 密码的“无头”输入，可能需在终端输入 GPG 密码；CI 环境可配合 `gpg-agent` 或环境变量传入。

### 4. 在 Portal 中完成发布

- 上传成功后，打开 [Central Publisher Portal - Deployments](https://central.sonatype.com/publishing/deployments)
- 找到本次部署记录，等待状态变为 **Validated**
- 点击 **Publish**，将本次部署发布到 Maven Central

发布完成后，用户即可通过 Maven Central 解析到你的插件（通常几小时内在 [search.maven.org](https://search.maven.org/) 可搜到）。

### 5. 使用个人 groupId 时需改的 pom 片段

在未被 Checker Framework 接纳前，建议用 **`io.github.你的GitHub用户名`** 发布。需在 `pom.xml` 中统一改成你的信息（把 `YOUR_GITHUB_USERNAME` 和仓库路径换成实际值）：

```xml
<groupId>io.github.YOUR_GITHUB_USERNAME</groupId>
<artifactId>checkerframework-maven-plugin</artifactId>
...
<url>https://github.com/YOUR_GITHUB_USERNAME/checker-maven-plugin</url>
...
<developers>
    <developer>
        <name>你的名字或昵称</name>
        <url>https://github.com/YOUR_GITHUB_USERNAME</url>
    </developer>
</developers>
<scm>
    <connection>scm:git:git://github.com/YOUR_GITHUB_USERNAME/checker-maven-plugin.git</connection>
    <developerConnection>scm:git:ssh://github.com:YOUR_GITHUB_USERNAME/checker-maven-plugin.git</developerConnection>
    <url>https://github.com/YOUR_GITHUB_USERNAME/checker-maven-plugin</url>
</scm>
```

发布后，用户引用方式示例：

```xml
<plugin>
  <groupId>io.github.YOUR_GITHUB_USERNAME</groupId>
  <artifactId>checkerframework-maven-plugin</artifactId>
  <version>1.0.0</version>
  ...
</plugin>
```

---

## 二、发布到 GitHub Packages（备选）

若暂时不发布到 Maven Central，可以发布到 [GitHub Packages](https://docs.github.com/en/packages)，用户需要在其 `pom.xml` 或 `~/.m2/settings.xml` 里配置 GitHub 仓库才能使用。

1. 在 GitHub 创建 **Personal Access Token**（需包含 `write:packages`、`read:packages`）
2. 在项目 `pom.xml` 的 `<project>` 下增加：

```xml
<distributionManagement>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/你的用户名/checker-maven-plugin</url>
  </repository>
</distributionManagement>
```

3. 在 `~/.m2/settings.xml` 的 `<servers>` 中增加：

```xml
<server>
  <id>github</id>
  <username>你的 GitHub 用户名</username>
  <password>你的 Personal Access Token</password>
</server>
```

4. 执行：

```bash
mvn clean deploy
```

使用方需在 `settings.xml` 或项目 `pom.xml` 中配置该 GitHub Packages 仓库才能解析你的插件。

---

## 三、本地安装（仅本机使用）

若只想在本机其他项目里试用，无需发布到任何远程仓库：

```bash
mvn clean install
```

构件会安装到本机 `~/.m2/repository`。在同一台机器上的其他 Maven 项目里，只要在 `pom.xml` 里用相同的 `groupId`、`artifactId` 和 `version` 引用该插件即可。

---

## 四、发布前检查清单

- [ ] `pom.xml` 中 `<version>` 为正式版（不含 `-SNAPSHOT`）
- [ ] 已运行 `mvn clean verify` 且测试通过
- [ ] 已在 Central Portal 验证 namespace（或已改用并验证 `io.github.xxx`）
- [ ] 已配置 GPG 并上传公钥
- [ ] 已在 `~/.m2/settings.xml` 中配置 Central 的 Token（或 GitHub Token）
- [ ] 若修改了 `groupId`，已同步修改 `pom.xml` 中的 `<url>`、`<scm>`、`<developers>` 等

完成以上步骤后，按「一、发布到 Maven Central」执行 `mvn clean deploy` 并在 Portal 中点击 Publish 即可对外发布。
