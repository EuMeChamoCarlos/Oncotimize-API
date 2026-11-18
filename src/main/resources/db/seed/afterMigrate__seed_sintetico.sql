-- =====================================================================
-- oncotimizeAPI - seed sintético (só profile dev; callback afterMigrate)
-- Idempotente: só insere o que falta; nunca sobrescreve dado existente.
--
-- Fontes:
--   FARMÁCIA = tabela da farmácia da LMECC (Tabela_Quimioterapicos_TCC_UFERSA.xlsx, 17/11/2026):
--              apresentações e preço médio em R$ (referência Nov/2026).
--   TASY     = só o formato e os nomes comerciais das apresentações, como aparecem no Tasy.
--   FICTÍCIO = estoques, doses, datas e códigos inventados. Nenhuma linha vem de paciente real.
--
-- Doses = dose/m² (ou /kg) × superfície corporal (ou peso) fictícia.
-- Mais apresentações (quando a farmácia informar): acrescentar uma linha em "Apresentações"
-- ou cadastrar por POST /api/medicamentos/{id}/frascos.
-- =====================================================================

-- Medicamentos IV da tabela da farmácia. Azacitidina e Bortezomibe (SC) ficam de fora: o modelo
-- trata só a via intravenosa. Metotrexato entra porque também é usado IV.
-- Trastuzumabe (MED005) saiu em 17/11: chega por ordem judicial, um frasco de 440 mg por paciente; até a
-- farmácia confirmar se a sobra pode ir para outro paciente, não entra na otimização.
INSERT INTO medicamento (nome, codigo_interno) VALUES
    ('Paclitaxel',     'MED001'),
    ('Carboplatina',   'MED002'),
    ('Ciclofosfamida', 'MED003'),
    ('Doxorrubicina',  'MED004'),
    ('Metotrexato',    'MED006')
ON CONFLICT DO NOTHING;

-- Apresentações: (medicamento, volume mg, custo R$ = FARMÁCIA, estoque = FICTÍCIO).
INSERT INTO apresentacao_frasco (medicamento_id, volume_mg, custo, quantidade_estoque)
SELECT m.id, v.volume, v.custo, v.estoque
FROM (VALUES
    ('Paclitaxel',      300,   79.89, 30),   -- 6 mg/mL, 50 mL
    ('Carboplatina',    450,  208.13, 15),   -- 10 mg/mL, 45 mL
    ('Ciclofosfamida', 1000,  108.00, 20),   -- pó liofilizado 1 g
    ('Doxorrubicina',    50,   41.70, 20),
    ('Metotrexato',      50,   16.02, 30),   -- 25 mg/mL, 2 mL
    ('Metotrexato',    1000,   56.40, 10)    -- 100 mg/mL, 20 mL
) AS v(nome, volume, custo, estoque)
JOIN medicamento m ON m.nome = v.nome
WHERE NOT EXISTS (
    SELECT 1 FROM apresentacao_frasco f WHERE f.medicamento_id = m.id AND f.volume_mg = v.volume
);

-- Nome comercial da apresentação, como na coluna Medicamento do Tasy.
UPDATE apresentacao_frasco f
SET descricao_tasy = v.descricao
FROM (VALUES
    ('Paclitaxel',     300, 'Paclitaxel 6mg/ml Frasco-ampola 50ml'),
    ('Doxorrubicina',   50, 'Cloridrato de Doxorrubicina 50mg Frasco-ampola'),
    ('Ciclofosfamida', 1000, 'Ciclofosfamida 25mg/mL Frasco-ampola 40mL')
) AS v(nome, volume, descricao)
JOIN medicamento m ON m.nome = v.nome
WHERE f.medicamento_id = m.id AND f.volume_mg = v.volume AND f.descricao_tasy IS NULL;

-- =====================================================================
-- Cenário do plano do dia (FICTÍCIO): segunda, 01/03/2027, com todos os medicamentos.
--   TAX W: paclitaxel 80 mg/m², 1 ciclo de 12 semanas (D1 a D78).
--   AC: doxorrubicina 60 mg/m² + ciclofosfamida 600 mg/m², 4 ciclos a cada 21 dias.
--   CARBO-PACLI: carboplatina AUC 5 + paclitaxel 175 mg/m², 6 ciclos a cada 21 dias.
--   MTX: metotrexato a cada 21 dias; o 000014 em alta dose.
-- O PTC2027-000006 foi avaliado como não apto: fica de fora da otimização.
-- PTC2027-000007..009 eram de Trastuzumabe (retirado em 17/11); os números ficaram vagos.
-- =====================================================================

INSERT INTO protocolo_paciente (codigo_protocolo, medicacao_subtipo, grupo, data_inicio, ciclos,
                                dias_intervalo, intervalo_minimo_dias)
VALUES
    ('PTC2027-000001', 'TAX W',        'Sistema Reprodutivo Feminino', DATE '2027-03-01', 1,  7,  5),
    ('PTC2027-000002', 'TAX W',        'Sistema Reprodutivo Feminino', DATE '2027-03-01', 1,  7,  5),
    ('PTC2027-000003', 'TAX W',        'Sistema Reprodutivo Feminino', DATE '2027-03-02', 1,  7,  5),
    ('PTC2027-000004', 'AC',           'Sistema Reprodutivo Feminino', DATE '2027-03-01', 4, 21, 19),
    ('PTC2027-000005', 'AC',           'Sistema Reprodutivo Feminino', DATE '2027-03-01', 4, 21, 19),
    ('PTC2027-000006', 'AC',           'Sistema Reprodutivo Feminino', DATE '2027-03-01', 4, 21, 19),
    ('PTC2027-000010', 'CARBO-PACLI',  'Sistema Reprodutivo Feminino', DATE '2027-03-01', 6, 21, 19),
    ('PTC2027-000011', 'CARBO-PACLI',  'Pulmão',                       DATE '2027-03-01', 6, 21, 19),
    ('PTC2027-000012', 'MTX',          'Sistema Reprodutivo Feminino', DATE '2027-03-01', 4, 21, 19),
    ('PTC2027-000013', 'MTX',          'Sistema Reprodutivo Feminino', DATE '2027-03-01', 4, 21, 19),
    ('PTC2027-000014', 'MTX AD',       'Hematologia',                  DATE '2027-03-01', 4, 21, 19)
ON CONFLICT (codigo_protocolo) DO NOTHING;

INSERT INTO protocolo_medicamento (protocolo_id, medicamento_id, dose_mg, via, dias_aplicacao)
SELECT p.id, m.id, v.dose, 'IV', v.dias
FROM (VALUES
    ('PTC2027-000001', 'Paclitaxel',      128, 'D1,D8,D15,D22,D29,D36,D43,D50,D57,D64,D71,D78'),  -- SC 1,60
    ('PTC2027-000002', 'Paclitaxel',      140, 'D1,D8,D15,D22,D29,D36,D43,D50,D57,D64,D71,D78'),  -- SC 1,75
    ('PTC2027-000003', 'Paclitaxel',      120, 'D1,D8,D15,D22,D29,D36,D43,D50,D57,D64,D71,D78'),  -- SC 1,50
    ('PTC2027-000004', 'Doxorrubicina',   102, 'D1'),                                             -- SC 1,70
    ('PTC2027-000004', 'Ciclofosfamida', 1020, 'D1'),
    ('PTC2027-000005', 'Doxorrubicina',   111, 'D1'),                                             -- SC 1,85
    ('PTC2027-000005', 'Ciclofosfamida', 1110, 'D1'),
    ('PTC2027-000006', 'Doxorrubicina',    99, 'D1'),                                             -- SC 1,65
    ('PTC2027-000006', 'Ciclofosfamida',  990, 'D1'),
    ('PTC2027-000010', 'Carboplatina',    600, 'D1'),
    ('PTC2027-000010', 'Paclitaxel',      298, 'D1'),                                             -- SC 1,70
    ('PTC2027-000011', 'Carboplatina',    520, 'D1'),
    ('PTC2027-000011', 'Paclitaxel',      315, 'D1'),                                             -- SC 1,80
    ('PTC2027-000012', 'Metotrexato',      70, 'D1'),
    ('PTC2027-000013', 'Metotrexato',     180, 'D1'),
    ('PTC2027-000014', 'Metotrexato',    1500, 'D1')
) AS v(codigo, nome, dose, dias)
JOIN protocolo_paciente p ON p.codigo_protocolo = v.codigo
JOIN medicamento m ON m.nome = v.nome
ON CONFLICT (protocolo_id, medicamento_id) DO NOTHING;

-- TAX W: 1 ciclo com 12 aplicações semanais (D1 a D78).
INSERT INTO aplicacao (protocolo_id, numero, ciclo_numero, dia_ciclo, data_prevista, data_agendada,
                       dias_antes, dias_depois, status)
SELECT p.id, n, 1, 7 * n - 6, p.data_inicio + 7 * (n - 1), p.data_inicio + 7 * (n - 1), 0, 0, 'AGENDADA'
FROM protocolo_paciente p
CROSS JOIN LATERAL generate_series(1, 12) AS n
WHERE p.codigo_protocolo BETWEEN 'PTC2027-000001' AND 'PTC2027-000003'
ON CONFLICT DO NOTHING;

-- Demais: um D1 por ciclo, a cada dias_intervalo.
INSERT INTO aplicacao (protocolo_id, numero, ciclo_numero, dia_ciclo, data_prevista, data_agendada,
                       dias_antes, dias_depois, status)
SELECT p.id, n, n, 1, p.data_inicio + p.dias_intervalo * (n - 1), p.data_inicio + p.dias_intervalo * (n - 1),
       0, 0, 'AGENDADA'
FROM protocolo_paciente p
CROSS JOIN LATERAL generate_series(1, p.ciclos) AS n
WHERE p.codigo_protocolo BETWEEN 'PTC2027-000004' AND 'PTC2027-000014'
ON CONFLICT DO NOTHING;

-- Prescrição só da primeira aplicação, emitida no próprio dia: uma linha por quimioterápico, com o
-- mesmo código (PTC2027-000004 → PRE2027-000004).
INSERT INTO prescricao (codigo_prescricao, medicamento_id, dose_mg, status, data_prescricao, aplicacao_id)
SELECT 'PRE2027-' || right(p.codigo_protocolo, 6), pm.medicamento_id, pm.dose_mg,
       CASE WHEN p.codigo_protocolo = 'PTC2027-000006' THEN 'NAO_APTO' ELSE 'APTO' END, a.data_prevista, a.id
FROM aplicacao a
JOIN protocolo_paciente p ON p.id = a.protocolo_id
JOIN protocolo_medicamento pm ON pm.protocolo_id = p.id
WHERE p.codigo_protocolo BETWEEN 'PTC2027-000001' AND 'PTC2027-000014'
  AND a.numero = 1
ON CONFLICT DO NOTHING;

-- =====================================================================
-- Cenário da agenda (FICTÍCIO): paclitaxel 175 mg/m² a cada 21 dias (mínimo 19). Nove protocolos têm
-- aplicação em terça, 22/06/2027 (2.410 mg: 9 frascos, sobra de 290 mg). O PTC2027-000110 cai sozinho
-- na segunda, 21/06 (3ª aplicação, 280 mg), com janela de +1 dia: a prévia deve sugerir juntá-lo ao
-- grupo de 22/06, onde a dose cabe na sobra (ganho de 1 frasco, R$ 79,89).
-- =====================================================================

INSERT INTO protocolo_paciente (codigo_protocolo, medicacao_subtipo, grupo, data_inicio, ciclos,
                                dias_intervalo, intervalo_minimo_dias)
SELECT v.codigo, 'PACLITAXEL 21D', 'Sistema Reprodutivo Feminino', v.inicio::date, v.total, 21, 19
FROM (VALUES
    ('PTC2027-000101', '2027-06-22', 6),   -- 22/06 = 1ª aplicação
    ('PTC2027-000102', '2027-06-01', 6),   -- 22/06 = 2ª
    ('PTC2027-000103', '2027-05-11', 6),   -- 22/06 = 3ª
    ('PTC2027-000104', '2027-04-20', 6),   -- 22/06 = 4ª
    ('PTC2027-000105', '2027-06-22', 4),
    ('PTC2027-000106', '2027-06-01', 4),
    ('PTC2027-000107', '2027-05-11', 6),
    ('PTC2027-000108', '2027-06-22', 6),
    ('PTC2027-000109', '2027-06-01', 4),
    ('PTC2027-000110', '2027-05-10', 6)    -- 10/05, 31/05, 21/06 (isolado), 12/07...
) AS v(codigo, inicio, total)
ON CONFLICT (codigo_protocolo) DO NOTHING;

INSERT INTO protocolo_medicamento (protocolo_id, medicamento_id, dose_mg, via, dias_aplicacao)
SELECT p.id, m.id, v.dose, 'IV', 'D1'
FROM (VALUES
    ('PTC2027-000101', 255), ('PTC2027-000102', 262), ('PTC2027-000103', 270),
    ('PTC2027-000104', 258), ('PTC2027-000105', 296), ('PTC2027-000106', 266),
    ('PTC2027-000107', 275), ('PTC2027-000108', 260), ('PTC2027-000109', 268),
    ('PTC2027-000110', 280)
) AS v(codigo, dose)
JOIN protocolo_paciente p ON p.codigo_protocolo = v.codigo
JOIN medicamento m ON m.nome = 'Paclitaxel'
ON CONFLICT (protocolo_id, medicamento_id) DO NOTHING;

INSERT INTO aplicacao (protocolo_id, numero, ciclo_numero, dia_ciclo, data_prevista, data_agendada,
                       dias_antes, dias_depois, status)
SELECT p.id, n, n, 1, p.data_inicio + (n - 1) * p.dias_intervalo, p.data_inicio + (n - 1) * p.dias_intervalo,
       0, CASE WHEN p.codigo_protocolo = 'PTC2027-000110' THEN 1 ELSE 0 END, 'AGENDADA'
FROM protocolo_paciente p
CROSS JOIN LATERAL generate_series(1, p.ciclos) AS n
WHERE p.codigo_protocolo BETWEEN 'PTC2027-000101' AND 'PTC2027-000110'
ON CONFLICT DO NOTHING;

-- Prescrições que liberam as aplicações de 21 e 22/06, emitidas no próprio dia
-- (PTC2027-000107 → PRE2027-000207).
INSERT INTO prescricao (codigo_prescricao, medicamento_id, dose_mg, status, data_prescricao, aplicacao_id)
SELECT 'PRE2027-0002' || right(p.codigo_protocolo, 2), pm.medicamento_id, pm.dose_mg, 'APTO', a.data_prevista, a.id
FROM aplicacao a
JOIN protocolo_paciente p ON p.id = a.protocolo_id
JOIN protocolo_medicamento pm ON pm.protocolo_id = p.id
WHERE p.codigo_protocolo BETWEEN 'PTC2027-000101' AND 'PTC2027-000110'
  AND a.data_prevista IN (DATE '2027-06-21', DATE '2027-06-22')
ON CONFLICT DO NOTHING;

-- Admin de desenvolvimento (FICTÍCIO). Desde 17/11 só ADMIN cadastra usuário, então um banco novo
-- precisa de um primeiro admin para usar a API.
--   login: admin@oncotimize.test   senha: admin-dev-oncotimize   (hash BCrypt abaixo, custo 10)
-- Credencial de teste, só do profile dev: nunca subir o profile dev contra um banco real.
INSERT INTO auths (username, password)
VALUES ('admin@oncotimize.test', '$2a$10$Ehawdr69fAezUpkmfOnYEeJB2eslvfdW3UpwK2/6UoktnSYsH6pZK')
ON CONFLICT (username) DO NOTHING;

WITH a AS (
    SELECT au.id FROM auths au
    WHERE au.username = 'admin@oncotimize.test'
      AND NOT EXISTS (SELECT 1 FROM users u WHERE u.auth_id = au.id)
), p AS (
    INSERT INTO profiles (name) SELECT 'Administrador (dev)' FROM a RETURNING id
)
INSERT INTO users (status, auth_id, profile_id, role_id)
SELECT 'ACTIVE', a.id, p.id, r.id
FROM a, p, roles r
WHERE r.name = 'ADMIN';
