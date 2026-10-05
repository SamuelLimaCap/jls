# jls — correções locais (lvim-java)

Branch `correcoes-locais`, criada a partir do upstream `idelice/jls` no commit `e7b5294` (fix da issue #50).

## Correções
- **Índice Lombok O(F²) → O(F)** (`WorkspaceTypeIndex.generatedAccessorFieldName`): a indexação de um projeto multimódulo grande caiu de 647 s para 23 s.

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
