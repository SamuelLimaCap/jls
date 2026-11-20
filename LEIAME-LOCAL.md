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
- **Autocomplete em lambdas/streams** (`ParseTypeResolver`): `forEach` (lista e stream), `removeIf`,
  `Optional.map/ifPresent`, `reduce`, lambdas com 2+ parâmetros (`(k, v) ->`), `List.of(x)`/
  `Arrays.asList(x)`/`Optional.ofNullable(x)` (genérico do método ligado ao argumento) e o 2º genérico
  de `Map<K, V>` (`get`, `values`, `forEach`). Antes caíam em `Object`.
  Limite conhecido: membros de classes aninhadas de biblioteca (`Map.Entry`) não aparecem — o índice
  externo só conhece classes de primeiro nível.
- **Comando `java.expressionType`** (`ExpressionTypeAt`): `[uri, linha, coluna]` → tipo atribuído pelo
  javac da maior expressão que termina ali (`{type: "List<String>", qualified, imports}`). Usado pelo
  postfix `.var` do lvim-java (`lua/lvimjava/postfix.lua`).
- **Índice em paralelo**: parse + stubs do Lombok + extração por arquivo
  rodam em N threads (`JLS_INDEX_THREADS`, padrão = núcleos − 1, máx. 8); a gravação nos mapas é feita
  na ordem original, então o índice é idêntico (despejo de 211 mil linhas comparado). Junto:
  - `Parser` com um file manager por thread e classpath vazio (só faz parse; o classpath padrão eram
    os jars do próprio jls, relidos a cada arquivo — ~25% do tempo);
  - cache de `generatedAccessorFieldName` calculado fora do lock;
  - `externalIndexForIndexing` memorizado por arquivo no pós-processamento.
  Índice do `app`: 12,4–13,2 s → 6,3–6,9 s (autocomplete pronto em ~4 s em vez de ~13 s). Custo: nos
  primeiros segundos o índice disputa CPU com a primeira compilação (1º `gd` 7,0 s vs 4,0–5,1 s, 1 medição).
- **RAM do índice**: membros compartilham textos repetidos (`String.intern`, arrays vazios e conjuntos
  de modificadores únicos — 1,8 milhão de textos, só 23% distintos) e os índices guardados para voltar
  a um módulo ficam em `SoftReference` (a JVM descarta um parado ou quando falta memória; o módulo
  reconstrói). Com `app`+`mod2`+`mod3`+`core`+`mod4` abertos: heap 820 → 561 MB, RSS 1,33 → ~1,05 GB
  (somando as flags da JVM no `java.lua` do lvim-java). Índices idênticos (despejo comparado).
- **Correção** da corrida do índice paralelo que podia travar a construção (vaga liberada antes de
  completada → NPE no worker → espera infinita).
- **Debug (`JavaDebugServer`)**: breakpoints aplicados a TODAS as classes do arquivo (classes internas
  e a cópia de cada redeploy no WildFly) — antes ia só na primeira encontrada, que podia ser a interna
  ou a do deploy antigo (breakpoint "verificado" que nunca parava); classes carregadas depois de
  conectar também recebem os breakpoints; busca das classes filtrada pelo nome (antes consultava o
  arquivo-fonte de todas as classes do servidor, uma ida à JVM por classe).
  No lvim-java, o adaptador de debug usa os mesmos flags da JVM do servidor (`lua/lvimjava/jvm.lua`):
  com flags diferentes o cache AOT compartilhado falhava e a JVM escrevia o erro na saída padrão,
  corrompendo o protocolo — o nvim-dap fechava a sessão na hora.
- **Debug: variáveis e avaliação** (`JdiInspector`): objetos, arrays, listas e mapas expansíveis no painel
  (e o `this`); avaliação de expressões Java no frame parado — nomes, `a.b.c`, chamadas de método,
  `arr[i]`, operadores — executadas na JVM depurada (antes: Groovy sobre os espelhos do JDI, inútil para
  objetos). `toString()` automático só em tipos simples do JDK (em entidades JPA ele pode disparar lazy
  loading com a JVM parada).
- **Debug: resumo dos objetos** (`JdiInspector.summary`): `Nota{numero=9005, serie="0065", status=AUTORIZADA,
  valor=45.90, …}` no lugar de `Nota (id=812)` — os 4 primeiros campos, lidos direto pela JVM (nenhum
  método roda; BigDecimal remontado do `intCompact`/`scale`); proxy do Hibernate vira `Nota (proxy
  Hibernate)`; `ArrayList` mostra os 3 primeiros itens. Teste: `JavaDebugServerTest.summarizesObjectsWithoutInvoking`.
- **Debug: fonte do JDK e de bibliotecas** (`LibrarySources`): frames de classes fora dos
  `sourceRoots` (JDK, Spring...) iam sem caminho e o nvim-dap não mostrava nada — p.ex. ao dar step
  over num `throw new ServiceException` que o Spring captura, a parada cai no `catch` do Spring.
  Agora: JDK → `src.zip` do JDK local com a versão da JVM depurada; biblioteca → jar do `~/.m2`
  (e `$JBOSS_HOME/modules`) que tem a classe — com várias versões, vence a cuja tabela de linhas
  bate com a classe carregada — usando o `-sources.jar` dele, ou o Vineflower com o mapa de linhas
  (a seta e os breakpoints no arquivo descompilado caem na linha certa). Arquivos em
  `~/.cache/jls-jar-sources-debug/` (o nvim-jls abre como fonte externo, somente leitura).
  Índice dos ~1.500 jars: ~1 s em segundo plano ao conectar; 1º `stackTrace` ~0,8 s.
- **Debug: envio sincronizado** das mensagens do adaptador: respostas e eventos saíam de duas threads e
  podiam se misturar, corrompendo o protocolo.
- **Lançadores (`dist/launch_*.sh`)**: o passo que cria o cache AOT escrevia na saída padrão antes do
  protocolo começar — derrubava o servidor de linguagem ou o debug uma vez a cada jar novo.
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

- **Autocomplete com o nome colado num ponto** (`CompletionProvider.memberAccessContext`): em `nome|.metodo()` o
  cursor pulava o ponto e o servidor listava membros de `nome`, em vez de completar o identificador. O avanço
  agora só vale entre dois pontos (`x.|.y`). Teste: `CompletionsTest.identifierRightBeforeExistingDot`.
- **`JLS_NO_INLAY_PUSH=1`** (`JavaLanguageServer`): não calcula os inlay hints enviados a cada compilação de
  diagnóstico. Eles rodam na thread do LSP e abrem os jars do classpath para cada chamada de biblioteca: ~3 s
  de bloqueio por edição num arquivo de 1.950 linhas, com autocomplete parado. Ligado no `cmd_env` do `java.lua`.

- **Autocomplete depois de `session.createQuery(hql).`** (`ParseTypeResolver`, `ApiStubJars`, 16/11/2026):
  não aparecia nada (nem `setParameter`/`uniqueResult`). Três causas em cadeia:
  1. o class loader da reflexão pegava `javax.persistence.*` do `javaee-web-api-6.0.jar` (jar só de
     API, sem corpo de método → `ClassFormatError`) antes do `hibernate-jpa-2.1-api`; agora os jars
     `javaee(-web)-api-N` / `jakartaee(-web)-api-N` vão para o fim (`ApiStubJars.stubsLast`, também no
     class loader do índice externo);
  2. `Session.getMethods()` precisa de `javax.naming.Referenceable` (o runtime do jls não tem
     `java.naming`): os passos de refinamento por reflexão (lambda, `Class<T>`, genérico do método)
     agora falham sozinhos (`guardReflection`) e a resolução segue pelo tipo de retorno do índice, em
     vez de abortar a expressão inteira;
  3. sobrecarga: o método era escolhido só pelo nome (`rawMember`); agora `resolveOverloadedReturnType`
     filtra por quantidade e tipo dos argumentos e, entre retornos covariantes, fica o mais específico.
  4. (17/11) `query.set` numa linha acima de `query.setDate(...);` vira, no parse, a declaração
     `query.set query.setDate(...)`; a variável falsa `query` (tipo `query.set`) escondia a de verdade.
     `visitVariable` não põe no escopo a variável cujo tipo contém o cursor. `Query`/`NativeQuery`
     `query.set` 0 → 40/41 itens (com `setParameter`).
  Teste (java-sinc, `nfe/jar`): `getCurrentSession().createQuery(X).set` 0 → 40 itens (com `setParameter`),
  `.setParameter(...).uni` 0 → `uniqueResult`; stream/String/Optional/lista de DAO iguais ao jar anterior.

## Gerar e instalar no lvim-java
```bash
cd ~/jls
# precisa de JDK 25: usa o runtime que já vem com o jls instalado
JAVA_HOME=~/.local/share/lvim-java/jls/linux mvn -q -Dmaven.test.skip=true package
# copiar e RENOMEAR: um jls aberto continua lendo o jar antigo (cp por cima quebra a sessão aberta)
cp dist/classpath/jls.jar ~/.local/share/lvim-java/jls/classpath/.jls.jar.novo
mv ~/.local/share/lvim-java/jls/classpath/.jls.jar.novo ~/.local/share/lvim-java/jls/classpath/jls.jar
```
(`-Dmaven.test.skip=true` porque os testes do upstream não compilam nesse commit.)

Depois, `:JlsRestart` no lvim-java.

## Atualizar com o upstream
```bash
git fetch origin && git rebase origin/master
```
