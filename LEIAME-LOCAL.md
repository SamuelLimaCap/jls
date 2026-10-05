# jls — correções locais (lvim-java)

Branch `correcoes-locais`, criada a partir do upstream `idelice/jls` no commit `e7b5294` (fix da issue #50).

## Correções
- **Índice Lombok O(F²) → O(F)** (`WorkspaceTypeIndex.generatedAccessorFieldName`): a indexação de um projeto multimódulo grande caiu de 647 s para 23 s.

## Gerar e instalar no lvim-java
```bash
cd ~/jls
mvn -q -DskipTests package          # gera target/jls.jar (ver o pom se o nome mudar)
cp target/jls*.jar ~/.local/share/lvim-java/jls/classpath/jls.jar
```
Depois, `:JlsRestart` no lvim-java.

## Atualizar com o upstream
```bash
git fetch origin && git rebase origin/master
```
