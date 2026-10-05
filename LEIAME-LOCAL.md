# jls — correções locais (lvim-java)

Branch `correcoes-locais`, criada a partir do upstream `idelice/jls` no commit `e7b5294` (fix da issue #50).

## Correções
- **Índice Lombok O(F²) → O(F)** (`WorkspaceTypeIndex.generatedAccessorFieldName`): a indexação de um projeto multimódulo grande caiu de 647 s para 23 s.

- **Binários do workspace** (opt-in, `JLS_WORKSPACE_BINARIES=1`): os módulos do workspace dos quais
  o arquivo depende (ex.: `core` para o `app`) entram pelo `target/classes`, ao lado do fonte.
  Com `-Xprefer:newer` o javac usa o `.class` do que não mudou e o fonte do que foi editado ou salvo
  depois do último build. Editar um arquivo desses módulos descarta o contexto reaproveitado do javac.
  - Primeira análise de um arquivo do `app`: 899 arquivos / 13,3 s → 2 arquivos / 2,5 s
    (primeiro `gd` 16,1 s → 5,5 s).
  - Método novo no `core` aparece no `app` sem `mvn install` (editado ou salvo).
  - Precisa existir `target/classes` do módulo dependido: um `mvn compile` nele (sem install) basta.
  - Classe apagada ou renomeada no módulo dependido continua visível até o próximo `mvn compile`.
  - Ligado no lvim-java via `cmd_env` do nvim-jls (`~/.config/lvim-java/lua/plugins/java.lua`).

## Gerar e instalar no lvim-java
```bash
cd ~/jls
# precisa de JDK 25: usa o runtime que já vem com o jls instalado
JAVA_HOME=~/.local/share/lvim-java/jls/linux mvn -q -Dmaven.test.skip=true package
cp dist/classpath/jls.jar ~/.local/share/lvim-java/jls/classpath/jls.jar
```
(`-Dmaven.test.skip=true` porque os testes do upstream não compilam nesse commit.)

Depois, `:JlsRestart` no lvim-java.

## Atualizar com o upstream
```bash
git fetch origin && git rebase origin/master
```
