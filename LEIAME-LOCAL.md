# jls — correções locais (lvim-java)

Branch `correcoes-locais`, criada a partir do upstream `idelice/jls` no commit `e7b5294` (fix da issue #50).

## Correções
- **Índice Lombok O(F²) → O(F)** (`WorkspaceTypeIndex.generatedAccessorFieldName`): a indexação de um projeto multimódulo grande caiu de 647 s para 23 s.

- **Índice: nomes de anotação/import sem pretty-print** (`LombokAnnotations.treeName` + imports
  pré-processados uma vez por arquivo): `isLombokAnnotation` convertia cada import do arquivo em texto
  (`Tree.toString()` → `Pretty.print`) para cada anotação de cada campo — 44% do build do índice.
  Índice do `app` (7.863 arquivos): 22,0 s → 12,9 s, conteúdo idêntico (despejo de 211 mil linhas comparado).
- **Índice não é refeito ao trocar de módulo**: se o índice atual já vê todas as pastas de fonte do
  módulo do arquivo aberto (o do `app` contém o `core`), ele é mantido — a consulta já é recortada
  por módulo (`ModuleCompiler.typeIndex`). Os últimos 3 índices ficam guardados: voltar a um módulo
  restaura o índice dele e aplica só os arquivos editados desde então (`FileStore.changedSince`).
  `app` → `core` → `app`: autocomplete pronto em 2,9 s e 0,4 s (antes: duas reconstruções de 8–15 s).
  Recorte do índice do `app` × índice do `core` construído do zero: despejos idênticos.
- **Binários do workspace** (opt-in, `JLS_WORKSPACE_BINARIES=1`): o próprio módulo e os módulos do
  workspace dos quais ele depende (ex.: `core` para o `app`) entram pelo `target/classes`, ao
  lado do fonte. O arquivo analisado é sempre compilado do fonte.
  Com `-Xprefer:newer` o javac usa o `.class` do que não mudou e o fonte do que foi editado ou salvo
  depois do último build. Editar um arquivo desses módulos descarta o contexto reaproveitado do javac.
  - Primeira análise de um arquivo do `app`: 899 arquivos / 13,3 s → 2 arquivos / 2,5 s
    (primeiro `gd` 16,1 s → 5,5 s).
  - Abrir um arquivo do `core`: 925 arquivos / 9,4 s → 1 arquivo / 3,1 s.
  - Método novo no `core` aparece no `app` sem `mvn install` (editado ou salvo).
  - Precisa existir `target/classes` dos módulos: um `mvn compile` (sem install) basta.
  - Classe apagada ou renomeada continua visível até o próximo `mvn compile`.
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
