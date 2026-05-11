Para um cenário, onde você precisa gerenciar desde pequenos caches (100 mil vetores) até um volume massivo de 15 milhões (150 GB), a escolha entre **SQLite** e **RocksDB** depende de quanto você prioriza a **facilidade de desenvolvimento (SQL)** versus a **performance bruta de escrita/leitura (Key-Value)**.

Ambos são **embarcados** (não exigem servidor) e **persistentes**. Aqui está o comparativo detalhado:

---

## 1. SQLite (implementação atual do projeto)

No estado atual do projeto, o SQLite é usado como um banco relacional embarcado em arquivo único, acessado por JDBC. Os embeddings são armazenados como `BLOB`, não com uma extensão vetorial como `sqlite-vec`.

### ✅ Vantagens

- **Facilidade de Uso (SQL):** Você usa a sintaxe que já conhece. Pode fazer `SELECT embedding FROM embeddings WHERE hash_key = ?`.
- **Ecossistema Java:** Integração via JDBC é padrão e muito estável.
- **Gerenciamento:** O banco é um arquivo `.db`. Mover, copiar ou fazer backup é um simples comando `cp`.
- **Flexibilidade:** Se no futuro você precisar adicionar colunas extras (como `source_url` ou `last_accessed`), é um simples `ALTER TABLE`.

### ❌ Desvantagens

- **Performance em Escala:** Em 15 milhões de registros, o índice B-Tree do SQLite pode ficar mais lento que o RocksDB para buscas por ID.
- **Concorrência de Escrita:** O SQLite trava o arquivo para escrita. Como a implementação atual não configura `WAL`, múltiplas escritas concorrentes podem se tornar gargalo mais cedo (erro `database is locked`).
- **Overhead do SQL:** O parser de SQL adiciona uma pequena latência em comparação ao acesso direto a bytes.

---

## 2. RocksDB

O RocksDB é uma biblioteca de armazenamento Key-Value de altíssima performance, otimizada para SSDs.

### ✅ Vantagens

- **Escalabilidade Massiva:** Foi feito para lidar com bilhões de chaves. Para os seus 15 milhões de vetores, ele nem "sua".
- **Performance de Escrita (LSM-Tree):** É muito mais rápido que o SQLite para inserir dados, pois transforma escritas aleatórias em sequenciais no disco.
- **Compressão Nativa:** O projeto atualmente já habilita compressão LZ4, reduzindo espaço em disco sem complicar o uso da API.
- **Eficiência para cache bruto:** Como o acesso é `get(key)` e `put(key, value)`, ele combina bem com o padrão atual do serviço.

### ❌ Desvantagens

- **Complexidade no Java:** Requer o uso de JNI (Java Native Interface). A API é baseada em `byte[]`, então a aplicação precisa serializar/deserializar manualmente os embeddings.
- **Opacidade:** Não há uma interface visual fácil para ver os dados. Você não consegue abrir um "DBeaver" e dar um `SELECT` facilmente sem ferramentas específicas.
- **Falta de SQL:** É estritamente `get(key)` e `put(key, value)`. Se precisar de filtros complexos no futuro, terá que implementar a lógica na aplicação.

---

## Comparativo de Performance por Cenário

| Cenário                   | SQLite                  | RocksDB              | Veredito                                   |
| ------------------------- | ----------------------- | -------------------- | ------------------------------------------ |
| **Pequeno (100k - 2M)**   | **Excelente**           | Muito Bom            | O SQLite é mais prático aqui.              |
| **Massivo (15M / 150GB)** | Médio (Pode sofrer I/O) | **Excelente**        | RocksDB gerencia melhor o arquivo gigante. |
| **Escritas Paralelas**    | Baixo (Fila de espera)  | **Muito Alto**       | RocksDB é imbatível em ingestão.           |
| **Desenvolvimento**       | Rápido (JDBC/SQL)       | Lento (Manual Bytes) | SQLite ganha em produtividade.             |

---

## Qual escolher para o seu projeto?

Se o seu fluxo de trabalho mensal de indexação for **altamente paralelo** (muitos processos requisitando e salvando vetores ao mesmo tempo), o **RocksDB** é a escolha técnica correta para evitar travamentos de arquivo.

No entanto, se você valoriza a **simplicidade** e quer um backend local sem servidor, o **SQLite** é hoje a opção mais simples no projeto. Para volumes maiores, ele continua viável, mas a ausência de `WAL` e o modelo de escrita serializado devem ser considerados antes de usá-lo como backend principal de ingestão pesada.

> **Dica Pro:** Como você está em Java, o **RocksDB** é frequentemente usado como o motor de cache de alto nível (como no Kafka Streams). Se você não tem medo de manipular `ByteBuffer`, ele é a solução definitiva para o problema dos 150 GB.
