package br.edu.ufersa.pw.oncotimize.experimentos;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.edu.ufersa.pw.oncotimize.domain.services.PlanejadorAgenda;
import br.edu.ufersa.pw.oncotimize.domain.services.PlanejadorAgenda.AplicacaoPlanejada;
import br.edu.ufersa.pw.oncotimize.domain.services.PlanejadorAgenda.Sugestao;
import br.edu.ufersa.pw.oncotimize.domain.services.SolverFracionamento;
import br.edu.ufersa.pw.oncotimize.domain.services.SolverFracionamento.Solucao;
import br.edu.ufersa.pw.oncotimize.domain.services.SolverFracionamento.TipoFrasco;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Experimentos da seção 4 do artigo: simulação calibrada pela rotina da LMECC. Não faz parte da suíte —
 * só roda com {@code .\mvnw.cmd test -Dtest=SimulacaoExperimentosTest -Dexperimentos=true} e grava CSV em
 * {@code target/experimentos}. Sem banco: usa direto o solver e o planejador do domínio.
 *
 * <p>Premissas (declaradas no artigo):
 * <ul>
 *   <li>Volume: 70, 135 e 150 preparos por dia útil (mínimo, típico e máximo informados pela farmácia, 19/11/2026).</li>
 *   <li>Parcela de cada medicamento: proporção na Tabela 4 de Gê et al. (2023), LMECC, 1 a 10/11/2019 — 288
 *       administrações, das quais paclitaxel 39, carboplatina 23, doxorrubicina 20, ciclofosfamida 13, metotrexato 2.</li>
 *   <li>Apresentações e preços: tabela da farmácia (nov. 2026); estoque folgado (não restringe).</li>
 *   <li>Doses: dose por m² dos esquemas usuais × superfície corporal ~ N(1,70; 0,18) limitada a [1,30; 2,30];
 *       carboplatina pela fórmula de Calvert, AUC 5, com taxa de filtração ~ N(90; 25) limitada a [30; 125].</li>
 * </ul>
 */
class SimulacaoExperimentosTest {

    private static final long SEMENTE = 2026L;
    private static final Path SAIDA = Path.of("target", "experimentos");

    // ---------------------------------------------------------------- medicamentos e frascos (tabela da farmácia)
    private enum Med {
        PACLITAXEL(List.of(new TipoFrasco(300, 79.89, 1000))),
        CARBOPLATINA(List.of(new TipoFrasco(450, 208.13, 1000))),
        CICLOFOSFAMIDA(List.of(new TipoFrasco(1000, 108.00, 1000))),
        DOXORRUBICINA(List.of(new TipoFrasco(50, 41.70, 1000))),
        METOTREXATO(List.of(new TipoFrasco(50, 16.02, 1000), new TipoFrasco(1000, 56.40, 1000)));

        final List<TipoFrasco> tipos;
        final UUID id = UUID.nameUUIDFromBytes(name().getBytes(StandardCharsets.UTF_8));

        Med(List<TipoFrasco> tipos) {
            this.tipos = tipos;
        }

        /** Apresentação de menor custo por miligrama (usada pelo compartilhamento sequencial). */
        TipoFrasco maisBaratoPorMg() {
            return tipos.stream().min((a, b) -> Double.compare(a.custo() / a.volumeMg(), b.custo() / b.volumeMg())).orElseThrow();
        }
    }

    // ---------------------------------------------------------------- esquemas (calibrados pela Tabela 4 de Gê)
    /** parcela288 = administrações do esquema em 288 (Gê et al., 2023, Tabela 4). */
    private enum Esquema {
        CARBO_PACLI(21, 19, 23),   // carboplatina AUC 5 + paclitaxel 175 mg/m²: frequência da carboplatina
        TAX_W(7, 5, 16),           // paclitaxel 80 mg/m² semanal: o restante do paclitaxel (39 − 23)
        AC(21, 19, 13),            // doxorrubicina 60 + ciclofosfamida 600 mg/m²: frequência da ciclofosfamida
        DOXO(21, 19, 7),           // doxorrubicina 60 mg/m² em outros esquemas: o restante (20 − 13)
        MTX(7, 5, 2);              // metotrexato 40 mg/m² semanal

        final int intervalo;
        final int intervaloMinimo;
        final int parcela288;

        Esquema(int intervalo, int intervaloMinimo, int parcela288) {
            this.intervalo = intervalo;
            this.intervaloMinimo = intervaloMinimo;
            this.parcela288 = parcela288;
        }

        /** Aplicações esperadas por dia útil, para um volume total de preparos. */
        double porDia(int volume) {
            return volume * parcela288 / 288.0;
        }

        Map<Med, Double> doses(Random r) {
            double sc = limitar(1.70 + 0.18 * r.nextGaussian(), 1.30, 2.30);
            Map<Med, Double> d = new LinkedHashMap<>();
            switch (this) {
                case CARBO_PACLI -> {
                    double tfg = limitar(90 + 25 * r.nextGaussian(), 30, 125);
                    d.put(Med.CARBOPLATINA, (double) Math.round(5 * (tfg + 25)));
                    d.put(Med.PACLITAXEL, (double) Math.round(175 * sc));
                }
                case TAX_W -> d.put(Med.PACLITAXEL, (double) Math.round(80 * sc));
                case AC -> {
                    d.put(Med.DOXORRUBICINA, (double) Math.round(60 * sc));
                    d.put(Med.CICLOFOSFAMIDA, (double) Math.round(600 * sc));
                }
                case DOXO -> d.put(Med.DOXORRUBICINA, (double) Math.round(60 * sc));
                case MTX -> d.put(Med.METOTREXATO, (double) Math.round(40 * sc));
                default -> throw new IllegalStateException();
            }
            return d;
        }
    }

    // ================================================================ E1 e E3: plano do dia e tempo do solver
    @Test
    void planoDoDia() throws IOException {
        assumeTrue(Boolean.getBoolean("experimentos"), "experimentos desligados (use -Dexperimentos=true)");
        Files.createDirectories(SAIDA);
        SolverFracionamento.resolver(List.of(100.0), Med.PACLITAXEL.tipos);  // aquece a biblioteca nativa
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(SAIDA.resolve("e1_plano_do_dia.csv")))) {
            csv.println("volume,dia,medicamento,pacientes,dose_total_mg,sem_custo,sem_frascos,sem_sobra_mg,"
                    + "seq_custo,seq_frascos,seq_sobra_mg,oti_custo,oti_frascos,oti_sobra_mg,oti_otima,oti_ms");
            for (int volume : new int[] {70, 135, 150}) {
                Random r = new Random(SEMENTE + volume);
                for (int dia = 1; dia <= 30; dia++) {
                    Map<Med, List<Double>> doses = new LinkedHashMap<>();
                    for (Esquema e : Esquema.values()) {
                        int n = poisson(e.porDia(volume), r);
                        for (int k = 0; k < n; k++) {
                            e.doses(r).forEach((m, d) -> doses.computeIfAbsent(m, x -> new ArrayList<>()).add(d));
                        }
                    }
                    for (Med m : Med.values()) {
                        List<Double> d = doses.getOrDefault(m, List.of());
                        if (d.isEmpty()) {
                            continue;
                        }
                        double total = d.stream().mapToDouble(Double::doubleValue).sum();
                        // sem compartilhamento: cada paciente com a melhor combinação só para ele
                        double semCusto = 0, semSobra = 0;
                        int semFrascos = 0;
                        for (double di : d) {
                            Solucao s = SolverFracionamento.resolver(List.of(di), m.tipos);
                            semCusto += s.custoTotal();
                            semFrascos += s.abertos().size();
                            semSobra += s.desperdicioMg();
                        }
                        // compartilhamento sequencial: a combinação de menor custo para a soma das doses do dia, com a
                        // sobra passada de um preparo ao seguinte (o farmacêutico que conhece a dose total do dia)
                        Solucao seq = SolverFracionamento.resolver(List.of(total), m.tipos);
                        int seqFrascos = seq.abertos().size();
                        double seqCusto = seq.custoTotal();
                        double seqSobra = seq.desperdicioMg();
                        // Oncotimize
                        long t0 = System.nanoTime();
                        Solucao o = SolverFracionamento.resolver(d, m.tipos);
                        double ms = (System.nanoTime() - t0) / 1e6;
                        csv.printf(Locale.ROOT, "%d,%d,%s,%d,%.0f,%.2f,%d,%.0f,%.2f,%d,%.0f,%.2f,%d,%.0f,%b,%.2f%n",
                                volume, dia, m, d.size(), total, semCusto, semFrascos, semSobra,
                                seqCusto, seqFrascos, seqSobra, o.custoTotal(), o.abertos().size(), o.desperdicioMg(),
                                o.otima(), ms);
                    }
                }
            }
        }
    }

    // ================================================================ E2: agenda
    @Test
    void agenda() throws IOException {
        assumeTrue(Boolean.getBoolean("experimentos"), "experimentos desligados (use -Dexperimentos=true)");
        Files.createDirectories(SAIDA);
        SolverFracionamento.resolver(List.of(100.0), Med.PACLITAXEL.tipos);
        LocalDate segunda = LocalDate.of(2027, 3, 1);  // semana planejada: 01 a 05/03/2027
        LocalDate sexta = segunda.plusDays(4);
        int volume = Integer.getInteger("experimentos.volume", 135);
        int sementes = Integer.getInteger("experimentos.sementes", 30);
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(SAIDA.resolve("e2_agenda.csv")))) {
            csv.println("volume,semente,janela,aplicacoes_semana,sugestoes,ms_planejador,nao_apto,aceite,aceitas,"
                    + "custo_base_previsto,custo_previsto,ganho_previsto,custo_base_realizado,custo_realizado,ganho_realizado,"
                    + "frascos_base_realizado,frascos_realizado");
            for (int s = 0; s < sementes; s++) {
                List<AplicacaoPlanejada> base = gerarAgenda(volume, new Random(SEMENTE * 31 + s), segunda);
                for (int janela : new int[] {1, 2}) {
                    List<AplicacaoPlanejada> apl = comJanela(base, janela, segunda, sexta);
                    long t0 = System.nanoTime();
                    List<Sugestao> sug = PlanejadorAgenda.planejar(apl, Map.of(), tipos(), segunda, sexta,
                            segunda.minusDays(1), fimDeSemanaFechado(segunda.minusDays(30), sexta.plusDays(30)));
                    double ms = (System.nanoTime() - t0) / 1e6;
                    Map<UUID, LocalDate> movidas = new HashMap<>();
                    sug.forEach(x -> movidas.put(x.aplicacaoId(), x.destino()));
                    double baseP = custoSemana(apl, Map.of(), Set.of(), segunda, sexta)[0];
                    double comP = custoSemana(apl, movidas, Set.of(), segunda, sexta)[0];
                    long naSemana = apl.stream().filter(a -> !a.data().isBefore(segunda) && !a.data().isAfter(sexta)).count();
                    for (double naoApto : new double[] {0.0, 0.10, 0.20}) {
                        for (double aceite : new double[] {1.0, 0.5}) {
                            // Números aleatórios comuns: os mesmos não aptos em todas as janelas e taxas de aceite da
                            // semana (gerador por semana e taxa), e os aceites com um gerador próprio.
                            Random rInaptos = new Random(SEMENTE * 97 + s * 1000L + Math.round(naoApto * 100));
                            Set<UUID> inaptos = new HashSet<>();
                            for (AplicacaoPlanejada a : base) {
                                if (rInaptos.nextDouble() < naoApto) {
                                    inaptos.add(a.id());
                                }
                            }
                            Random rAceite = new Random(SEMENTE * 89 + s * 1000L + Math.round(aceite * 100));
                            Map<UUID, LocalDate> aceitas = new HashMap<>();
                            for (Sugestao x : sug) {
                                if (rAceite.nextDouble() < aceite) {
                                    aceitas.put(x.aplicacaoId(), x.destino());
                                }
                            }
                            double[] baseR = custoSemana(apl, Map.of(), inaptos, segunda, sexta);
                            double[] comR = custoSemana(apl, aceitas, inaptos, segunda, sexta);
                            csv.printf(Locale.ROOT, "%d,%d,%d,%d,%d,%.0f,%.2f,%.1f,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.0f,%.0f%n",
                                    volume, s, janela, naSemana, sug.size(), ms, naoApto, aceite, aceitas.size(),
                                    baseP, comP, baseP - comP, baseR[0], comR[0], baseR[0] - comR[0], baseR[1], comR[1]);
                        }
                    }
                    csv.flush();
                }
            }
        }
    }

    // ================================================================ seed do banco (perfil dev) com uma semana simulada
    /**
     * Gera {@code src/main/resources/db/seed/afterMigrate__seed_sintetico_simulacao.sql}: uma semana (12 a 16/04/2027) com
     * o volume típico de 135 preparos por dia, pelo mesmo gerador da simulação, para testar a API e o front com volume
     * realista. Roda só com {@code -Dexperimentos.seed=true}; o SQL gerado é versionado.
     */
    @Test
    void gerarSeedDaSimulacao() throws IOException {
        assumeTrue(Boolean.getBoolean("experimentos.seed"), "geração do seed desligada (use -Dexperimentos.seed=true)");
        LocalDate segunda = LocalDate.of(2027, 4, 12);
        LocalDate sexta = segunda.plusDays(4);
        LocalDate de = segunda.minusWeeks(4);
        LocalDate ate = segunda.plusWeeks(4).plusDays(4);
        Random r = new Random(SEMENTE * 13);
        Map<Med, String> nome = Map.of(Med.PACLITAXEL, "Paclitaxel", Med.CARBOPLATINA, "Carboplatina",
                Med.CICLOFOSFAMIDA, "Ciclofosfamida", Med.DOXORRUBICINA, "Doxorrubicina", Med.METOTREXATO, "Metotrexato");
        Map<Esquema, String> subtipo = Map.of(Esquema.CARBO_PACLI, "CARBO-PACLI", Esquema.TAX_W, "TAX W", Esquema.AC, "AC",
                Esquema.DOXO, "DOXORRUBICINA", Esquema.MTX, "MTX");
        List<String> protocolos = new ArrayList<>(), medicamentos = new ArrayList<>(), aplicacoes = new ArrayList<>();
        int seq = 1001;
        int naSemana = 0;
        for (Esquema e : Esquema.values()) {
            List<LocalDate> diasDoCiclo = new ArrayList<>();
            for (LocalDate d = segunda; diasDoCiclo.size() < e.intervalo * 5 / 7; d = d.plusDays(1)) {
                if (util(d)) {
                    diasDoCiclo.add(d);
                }
            }
            for (LocalDate dia : diasDoCiclo) {
                int n = poisson(e.porDia(135), r);
                for (int k = 0; k < n; k++) {
                    String codigo = String.format("PTC2027-%06d", seq++);
                    LocalDate primeira = dia;
                    while (!primeira.minusDays(e.intervalo).isBefore(de)) {
                        primeira = primeira.minusDays(e.intervalo);
                    }
                    int numero = 0;
                    for (LocalDate d = primeira; !d.isAfter(ate); d = d.plusDays(e.intervalo)) {
                        numero++;
                        boolean semana = !d.isBefore(segunda) && !d.isAfter(sexta);
                        naSemana += semana ? 1 : 0;
                        int janela = semana ? 1 : 0;
                        aplicacoes.add(String.format("('%s', %d, '%s', %d, %d)", codigo, numero, d, janela, janela));
                    }
                    protocolos.add(String.format("('%s', '%s', 'Simulação', DATE '%s', %d, %d, %d)",
                            codigo, subtipo.get(e), primeira, numero, e.intervalo, e.intervaloMinimo));
                    e.doses(r).forEach((m, v) -> medicamentos.add(String.format(Locale.ROOT, "('%s', '%s', %.0f)", codigo, nome.get(m), v)));
                }
            }
        }
        Path destino = Path.of("src", "main", "resources", "db", "seed", "afterMigrate__seed_sintetico_simulacao.sql");
        try (PrintWriter sql = new PrintWriter(Files.newBufferedWriter(destino, StandardCharsets.UTF_8))) {
            sql.println("-- =====================================================================");
            sql.println("-- oncotimizeAPI - seed da SIMULAÇÃO (só profile dev; roda depois de afterMigrate__seed_sintetico.sql)");
            sql.println("-- GERADO por SimulacaoExperimentosTest#gerarSeedDaSimulacao (semente fixa): não editar à mão.");
            sql.println("-- Uma semana com o volume típico da LMECC (135 preparos por dia útil, informado pela farmácia),");
            sql.println("-- de 12 a 16/04/2027, com aplicações de 4 semanas antes a 4 depois (vizinhas para a agenda).");
            sql.println("-- Parcela por medicamento: Gê et al. (2023, Tabela 4). Doses FICTÍCIAS (dose/m² × superfície sorteada;");
            sql.println("-- carboplatina por Calvert, AUC 5). Só a semana de 12 a 16/04 tem prescrições (APTO) e janela de ±1 dia.");
            sql.printf("-- %d protocolos, %d aplicações, %d delas na semana.%n", protocolos.size(), aplicacoes.size(), naSemana);
            sql.println("-- Idempotente: só insere o que falta.");
            sql.println("-- =====================================================================");
            sql.println();
            sql.println("-- O estoque fictício do seed base não cobre um dia com esse volume: sobe para 200 frascos (nunca reduz).");
            sql.println("UPDATE apresentacao_frasco SET quantidade_estoque = 200 WHERE quantidade_estoque < 200;");
            sql.println();
            sql.println("INSERT INTO protocolo_paciente (codigo_protocolo, medicacao_subtipo, grupo, data_inicio, ciclos,");
            sql.println("                                dias_intervalo, intervalo_minimo_dias)");
            sql.println("VALUES");
            sql.println("    " + String.join(",\n    ", protocolos));
            sql.println("ON CONFLICT (codigo_protocolo) DO NOTHING;");
            sql.println();
            sql.println("INSERT INTO protocolo_medicamento (protocolo_id, medicamento_id, dose_mg, via, dias_aplicacao)");
            sql.println("SELECT p.id, m.id, v.dose, 'IV', 'D1'");
            sql.println("FROM (VALUES");
            sql.println("    " + String.join(",\n    ", medicamentos));
            sql.println(") AS v(codigo, nome, dose)");
            sql.println("JOIN protocolo_paciente p ON p.codigo_protocolo = v.codigo");
            sql.println("JOIN medicamento m ON m.nome = v.nome");
            sql.println("ON CONFLICT (protocolo_id, medicamento_id) DO NOTHING;");
            sql.println();
            sql.println("INSERT INTO aplicacao (protocolo_id, numero, ciclo_numero, dia_ciclo, data_prevista, data_agendada,");
            sql.println("                       dias_antes, dias_depois, status)");
            sql.println("SELECT p.id, v.numero, v.numero, 1, v.data::date, v.data::date, v.antes, v.depois, 'AGENDADA'");
            sql.println("FROM (VALUES");
            sql.println("    " + String.join(",\n    ", aplicacoes));
            sql.println(") AS v(codigo, numero, data, antes, depois)");
            sql.println("JOIN protocolo_paciente p ON p.codigo_protocolo = v.codigo");
            sql.println("ON CONFLICT DO NOTHING;");
            sql.println();
            sql.println("-- Prescrições da semana: uma linha por quimioterápico, com o mesmo código (PTC2027-001001 → PRE2027-001001).");
            sql.println("INSERT INTO prescricao (codigo_prescricao, medicamento_id, dose_mg, status, data_prescricao, aplicacao_id)");
            sql.println("SELECT 'PRE2027-' || right(p.codigo_protocolo, 6), pm.medicamento_id, pm.dose_mg, 'APTO', a.data_prevista, a.id");
            sql.println("FROM aplicacao a");
            sql.println("JOIN protocolo_paciente p ON p.id = a.protocolo_id");
            sql.println("JOIN protocolo_medicamento pm ON pm.protocolo_id = p.id");
            sql.printf("WHERE p.codigo_protocolo BETWEEN 'PTC2027-001001' AND 'PTC2027-%06d'%n", seq - 1);
            sql.printf("  AND a.data_prevista BETWEEN DATE '%s' AND DATE '%s'%n", segunda, sexta);
            sql.println("ON CONFLICT DO NOTHING;");
        }
    }

    /** Protocolos com início sorteado e aplicações a cada intervalo, de 4 semanas antes a 4 depois da semana planejada. */
    private static List<AplicacaoPlanejada> gerarAgenda(int volume, Random r, LocalDate segunda) {
        List<AplicacaoPlanejada> out = new ArrayList<>();
        LocalDate de = segunda.minusWeeks(4);
        LocalDate ate = segunda.plusWeeks(4);
        int seq = 0;
        for (Esquema e : Esquema.values()) {
            // dias úteis de um ciclo: cada um recebe Poisson(λ) protocolos que passam por ele
            List<LocalDate> diasDoCiclo = new ArrayList<>();
            for (LocalDate d = segunda; diasDoCiclo.size() < e.intervalo * 5 / 7; d = d.plusDays(1)) {
                if (util(d)) {
                    diasDoCiclo.add(d);
                }
            }
            for (LocalDate dia : diasDoCiclo) {
                int n = poisson(e.porDia(volume), r);
                for (int k = 0; k < n; k++) {
                    Map<UUID, Double> doses = new LinkedHashMap<>();
                    e.doses(r).forEach((m, v) -> doses.put(m.id, v));
                    UUID protocolo = UUID.nameUUIDFromBytes(("p" + seq).getBytes(StandardCharsets.UTF_8));
                    String codigo = String.format("SIM-%05d", seq++);
                    LocalDate primeira = dia;
                    while (!primeira.minusDays(e.intervalo).isBefore(de)) {
                        primeira = primeira.minusDays(e.intervalo);
                    }
                    int numero = 1;
                    for (LocalDate d = primeira; !d.isAfter(ate); d = d.plusDays(e.intervalo)) {
                        UUID id = UUID.nameUUIDFromBytes((codigo + "#" + numero).getBytes(StandardCharsets.UTF_8));
                        out.add(new AplicacaoPlanejada(id, protocolo, codigo, numero++, doses, d, d, d, e.intervaloMinimo, false));
                    }
                }
            }
        }
        return out;
    }

    /** Só as aplicações da semana planejada se movem, dentro de ±janela dias. */
    private static List<AplicacaoPlanejada> comJanela(List<AplicacaoPlanejada> base, int janela, LocalDate seg, LocalDate sex) {
        List<AplicacaoPlanejada> out = new ArrayList<>(base.size());
        for (AplicacaoPlanejada a : base) {
            boolean naSemana = !a.data().isBefore(seg) && !a.data().isAfter(sex);
            out.add(new AplicacaoPlanejada(a.id(), a.protocoloId(), a.codigoProtocolo(), a.numero(), a.doses(), a.data(),
                    naSemana ? a.data().minusDays(janela) : a.data(), naSemana ? a.data().plusDays(janela) : a.data(),
                    a.intervaloMinimoDias(), naSemana));
        }
        return out;
    }

    /** Custo e frascos da semana: soma do ótimo de cada medicamento em cada dia útil. */
    private static double[] custoSemana(List<AplicacaoPlanejada> apl, Map<UUID, LocalDate> movidas, Set<UUID> inaptos,
                                        LocalDate seg, LocalDate sex) {
        Map<LocalDate, Map<UUID, List<Double>>> porDia = new HashMap<>();
        for (AplicacaoPlanejada a : apl) {
            if (inaptos.contains(a.id())) {
                continue;
            }
            LocalDate d = movidas.getOrDefault(a.id(), a.data());
            if (d.isBefore(seg) || d.isAfter(sex)) {
                continue;  // fins de semana estão fechados: nenhum movimento sai da semana útil
            }
            a.doses().forEach((m, v) -> porDia.computeIfAbsent(d, x -> new HashMap<>()).computeIfAbsent(m, x -> new ArrayList<>()).add(v));
        }
        double custo = 0, frascos = 0;
        for (Map<UUID, List<Double>> dia : porDia.values()) {
            for (Map.Entry<UUID, List<Double>> e : dia.entrySet()) {
                Solucao s = SolverFracionamento.resolver(e.getValue(), tipos().get(e.getKey()));
                custo += s.custoTotal();
                frascos += s.abertos().size();
            }
        }
        return new double[] {custo, frascos};
    }

    private static Map<UUID, List<TipoFrasco>> tipos() {
        Map<UUID, List<TipoFrasco>> t = new HashMap<>();
        for (Med m : Med.values()) {
            t.put(m.id, m.tipos);
        }
        return t;
    }

    private static Map<UUID, Set<LocalDate>> fimDeSemanaFechado(LocalDate de, LocalDate ate) {
        Set<LocalDate> fds = new HashSet<>();
        for (LocalDate d = de; !d.isAfter(ate); d = d.plusDays(1)) {
            if (!util(d)) {
                fds.add(d);
            }
        }
        Map<UUID, Set<LocalDate>> m = new HashMap<>();
        for (Med med : Med.values()) {
            m.put(med.id, fds);
        }
        return m;
    }

    private static boolean util(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    private static int poisson(double lambda, Random r) {
        double l = Math.exp(-lambda), p = 1;
        int k = 0;
        do {
            k++;
            p *= r.nextDouble();
        } while (p > l);
        return k - 1;
    }

    private static double limitar(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
