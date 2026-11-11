// =========================================================================
// ECIRace GO - Infraestructura en Azure (Sprint 8)
// Azure Container Apps (plan Consumption, escala a cero) + PostgreSQL Flexible
// Server B1ms (se puede detener) + Log Analytics con tope diario.
// Se despliega con: python scripts/azure.py deploy
// =========================================================================

@description('Región de los recursos. Azure for Students limita las regiones permitidas.')
param location string = resourceGroup().location

@description('Región de PostgreSQL (por si B1ms no está disponible en la región principal).')
param dbLocation string = location

@description('Prefijo de nombres.')
param appName string = 'ecirace'

@description('Imagen del servidor, p. ej. ghcr.io/usuario/ecirace-go:latest')
param image string

@description('vCPU por réplica (0.5 con 1Gi es la combinación mínima razonable para la JVM).')
param cpu string = '0.5'

@description('Memoria por réplica.')
param memory string = '1Gi'

@description('0 = escala a cero (no cobra en reposo). 1 = siempre caliente (demo).')
@minValue(0)
@maxValue(1)
param minReplicas int = 0

// Las salas, el game loop y el broker STOMP viven en memoria: con más de una réplica
// los jugadores de una misma sala podrían quedar en procesos distintos.
// Por eso el máximo es 1 hasta que haya un broker externo (Redis/RabbitMQ).
var maxReplicas = 1

@description('Crear PostgreSQL Flexible Server. Si es false, la app usa H2 en memoria.')
param deployPostgres bool = true

param dbAdminUser string = 'eciraceadmin'

@secure()
@description('Contraseña del administrador de PostgreSQL.')
param dbPassword string = ''

@description('Servidor de registro privado (vacío si la imagen es pública).')
param registryServer string = ''
param registryUser string = ''
@secure()
param registryPassword string = ''

var tags = {
  proyecto: 'ECIRace GO'
  curso: 'ARSW'
  sprint: '8'
}
var dbName = 'ecirace'
var pgServerName = '${appName}-pg-${uniqueString(resourceGroup().id)}'
var useRegistry = !empty(registryServer)

// ---------------------------------------------------------------- Logs
resource logs 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: '${appName}-logs'
  location: location
  tags: tags
  properties: {
    sku: { name: 'PerGB2018' }
    retentionInDays: 30
    // Tope de ingesta: 0.1 GB/día ≈ 3 GB/mes, dentro de los 5 GB gratis mensuales
    workspaceCapping: { dailyQuotaGb: json('0.1') }
  }
}

// ---------------------------------------------------------------- Entorno
resource env 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: '${appName}-env'
  location: location
  tags: tags
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logs.properties.customerId
        sharedKey: logs.listKeys().primarySharedKey
      }
    }
    workloadProfiles: [
      { name: 'Consumption', workloadProfileType: 'Consumption' }
    ]
  }
}

// ---------------------------------------------------------------- PostgreSQL
resource pg 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = if (deployPostgres) {
  name: pgServerName
  location: dbLocation
  tags: tags
  sku: { name: 'Standard_B1ms', tier: 'Burstable' }
  properties: {
    version: '16'
    administratorLogin: dbAdminUser
    administratorLoginPassword: dbPassword
    storage: { storageSizeGB: 32, autoGrow: 'Disabled' }
    backup: { backupRetentionDays: 7, geoRedundantBackup: 'Disabled' }
    highAvailability: { mode: 'Disabled' }
    network: { publicNetworkAccess: 'Enabled' }
    authConfig: { passwordAuth: 'Enabled', activeDirectoryAuth: 'Disabled' }
  }
}

resource pgDb 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2024-08-01' = if (deployPostgres) {
  parent: pg
  name: dbName
  properties: { charset: 'UTF8', collation: 'en_US.utf8' }
}

// 0.0.0.0 = "permitir servicios de Azure" (las IP de salida de Container Apps no son fijas)
resource pgFirewall 'Microsoft.DBforPostgreSQL/flexibleServers/firewallRules@2024-08-01' = if (deployPostgres) {
  parent: pg
  name: 'AllowAzureServices'
  properties: { startIpAddress: '0.0.0.0', endIpAddress: '0.0.0.0' }
}

var dbSecrets = deployPostgres ? [
  { name: 'db-url', value: 'jdbc:postgresql://${pgServerName}.postgres.database.azure.com:5432/${dbName}?sslmode=require' }
  { name: 'db-user', value: dbAdminUser }
  { name: 'db-password', value: dbPassword }
] : []

var registrySecrets = useRegistry ? [
  { name: 'registry-password', value: registryPassword }
] : []

var dbEnv = deployPostgres ? [
  { name: 'DB_URL', secretRef: 'db-url' }
  { name: 'DB_USER', secretRef: 'db-user' }
  { name: 'DB_PASSWORD', secretRef: 'db-password' }
  { name: 'DB_DRIVER', value: 'org.postgresql.Driver' }
] : []

// ---------------------------------------------------------------- Container App
resource app 'Microsoft.App/containerApps@2024-03-01' = {
  name: '${appName}-app'
  location: location
  tags: tags
  dependsOn: [ pgDb, pgFirewall ]   // se ignoran si deployPostgres = false
  properties: {
    environmentId: env.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: true
        targetPort: 8080
        transport: 'auto'      // HTTP/1.1 con upgrade a WebSocket (/race-ws-native)
        allowInsecure: false   // HTTPS obligatorio: el GPS del celular solo funciona en contexto seguro
      }
      secrets: concat(dbSecrets, registrySecrets)
      registries: useRegistry ? [
        { server: registryServer, username: registryUser, passwordSecretRef: 'registry-password' }
      ] : []
    }
    template: {
      containers: [
        {
          name: 'ecirace-server'
          image: image
          resources: { cpu: json(cpu), memory: memory }
          env: concat([
            { name: 'SPRING_PROFILES_ACTIVE', value: 'cloud' }
          ], dbEnv)
          probes: [
            {
              type: 'Startup'   // la JVM + Hibernate tardan en 0.5 vCPU: hasta ~150 s
              httpGet: { path: '/actuator/health/liveness', port: 8080 }
              initialDelaySeconds: 10
              periodSeconds: 5
              failureThreshold: 30
            }
            {
              type: 'Liveness'
              httpGet: { path: '/actuator/health/liveness', port: 8080 }
              periodSeconds: 15
              failureThreshold: 3
            }
            {
              type: 'Readiness'
              httpGet: { path: '/actuator/health/readiness', port: 8080 }
              periodSeconds: 10
              failureThreshold: 3
            }
          ]
        }
      ]
      scale: {
        minReplicas: minReplicas
        maxReplicas: maxReplicas
        rules: [
          { name: 'http', http: { metadata: { concurrentRequests: '200' } } }
        ]
      }
    }
  }
}

output url string = 'https://${app.properties.configuration.ingress.fqdn}'
output fqdn string = app.properties.configuration.ingress.fqdn
output containerAppName string = app.name
output pgServerName string = deployPostgres ? pgServerName : ''
