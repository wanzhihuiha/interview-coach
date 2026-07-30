<template>
  <div ref="hostRef" class="training-architecture-scene" aria-hidden="true">
    <div v-if="!ready && !renderFailed" class="scene-loader">
      <span></span><span></span><span></span>
    </div>
    <div v-if="renderFailed" class="scene-fallback">
      <div class="fallback-stack">
        <span v-for="index in 5" :key="index"></span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js'
import { RoundedBoxGeometry } from 'three/examples/jsm/geometries/RoundedBoxGeometry.js'

interface TrainingStage {
  label: string
  status: string
  statusColor: string
  light: boolean
}

interface EnergyPacket {
  sprite: THREE.Sprite
  material: THREE.SpriteMaterial
  offset: number
  speed: number
}

const stages: TrainingStage[] = [
  { label: '简历准备', status: '已就绪', statusColor: '#e94c3a', light: false },
  { label: '岗位分析', status: '已匹配', statusColor: '#c93b30', light: false },
  { label: '模拟问答', status: '面试中', statusColor: '#ef8e84', light: false },
  { label: '逐题复盘', status: '待复盘', statusColor: '#e5ad3d', light: true },
  { label: '改进建议', status: '已完成', statusColor: '#5fd779', light: true }
]

const hostRef = ref<HTMLDivElement | null>(null)
const ready = ref(false)
const renderFailed = ref(false)

const geometries = new Set<THREE.BufferGeometry>()
const materials = new Set<THREE.Material>()
const textures = new Set<THREE.Texture>()

let scene: THREE.Scene | null = null
let renderer: THREE.WebGLRenderer | null = null
let camera: THREE.PerspectiveCamera | null = null
let visualRoot: THREE.Group | null = null
let environmentTarget: THREE.WebGLRenderTarget | null = null
let resizeObserver: ResizeObserver | null = null
let motionQuery: MediaQueryList | null = null
let animationFrame = 0
let reduceMotion = false
let lastTimestamp = 0

const cameraBase = new THREE.Vector3(9.4, 0.7, 22.3)
const cameraTarget = new THREE.Vector3(2.5, -0.1, 0)
const pointerTarget = new THREE.Vector2()
const pointerCurrent = new THREE.Vector2()
const energyPackets: EnergyPacket[] = []
const gapGlowMaterials: THREE.SpriteMaterial[] = []

let flowTexture: THREE.CanvasTexture | null = null
let contextMaterial: THREE.MeshBasicMaterial | null = null
let emitterMaterial: THREE.MeshBasicMaterial | null = null
let emitterWireMaterial: THREE.MeshBasicMaterial | null = null
let energyRing: THREE.Mesh | null = null
let greenLight: THREE.PointLight | null = null
let bluePulseLight: THREE.PointLight | null = null

function trackGeometry<T extends THREE.BufferGeometry>(geometry: T): T {
  geometries.add(geometry)
  return geometry
}

function trackMaterial<T extends THREE.Material>(material: T): T {
  materials.add(material)
  return material
}

function trackTexture<T extends THREE.Texture>(texture: T): T {
  textures.add(texture)
  return texture
}

function addRoundedRect(
  context: CanvasRenderingContext2D,
  x: number,
  y: number,
  width: number,
  height: number,
  radius: number
) {
  const safeRadius = Math.min(radius, width / 2, height / 2)
  context.beginPath()
  context.moveTo(x + safeRadius, y)
  context.lineTo(x + width - safeRadius, y)
  context.quadraticCurveTo(x + width, y, x + width, y + safeRadius)
  context.lineTo(x + width, y + height - safeRadius)
  context.quadraticCurveTo(x + width, y + height, x + width - safeRadius, y + height)
  context.lineTo(x + safeRadius, y + height)
  context.quadraticCurveTo(x, y + height, x, y + height - safeRadius)
  context.lineTo(x, y + safeRadius)
  context.quadraticCurveTo(x, y, x + safeRadius, y)
  context.closePath()
}

function createStageTexture(stage: TrainingStage, index: number, anisotropy: number) {
  const canvas = document.createElement('canvas')
  canvas.width = 2048
  canvas.height = 256
  const context = canvas.getContext('2d')
  if (!context) throw new Error('Canvas 2D context is unavailable')

  const foreground = stage.light ? '#14161a' : '#f4f2eb'
  context.clearRect(0, 0, canvas.width, canvas.height)
  context.textBaseline = 'middle'

  context.fillStyle = foreground
  context.font = '800 112px Arial, "Microsoft YaHei", "PingFang SC", sans-serif'
  context.fillText(`0${index + 1}`, 72, 132)

  context.font = '700 76px Arial, "Microsoft YaHei", "PingFang SC", sans-serif'
  context.fillText(stage.label, 292, 132)

  context.font = '700 31px "Microsoft YaHei", "PingFang SC", Arial, sans-serif'
  const statusWidth = Math.max(250, context.measureText(stage.status).width + 118)
  const statusX = canvas.width - statusWidth - 74
  const statusY = 74
  const statusHeight = 108

  addRoundedRect(context, statusX, statusY, statusWidth, statusHeight, 9)
  context.fillStyle = stage.light ? 'rgba(11, 13, 16, 0.72)' : 'rgba(4, 5, 8, 0.78)'
  context.fill()
  context.strokeStyle = stage.statusColor
  context.lineWidth = 3
  context.stroke()

  context.save()
  context.shadowColor = stage.statusColor
  context.shadowBlur = 22
  context.fillStyle = stage.statusColor
  context.beginPath()
  context.arc(statusX + 39, 128, 9, 0, Math.PI * 2)
  context.fill()
  context.restore()

  context.fillStyle = stage.statusColor
  context.fillText(stage.status, statusX + 66, 130)

  const texture = trackTexture(new THREE.CanvasTexture(canvas))
  texture.colorSpace = THREE.SRGBColorSpace
  texture.anisotropy = anisotropy
  texture.needsUpdate = true
  return texture
}

function createContextTexture() {
  const canvas = document.createElement('canvas')
  canvas.width = 512
  canvas.height = 2048
  const context = canvas.getContext('2d')
  if (!context) throw new Error('Canvas 2D context is unavailable')

  const horizontal = context.createLinearGradient(0, 0, canvas.width, 0)
  horizontal.addColorStop(0, 'rgba(112, 32, 27, 0.04)')
  horizontal.addColorStop(0.18, 'rgba(169, 46, 38, 0.34)')
  horizontal.addColorStop(0.5, 'rgba(233, 76, 58, 0.58)')
  horizontal.addColorStop(0.82, 'rgba(169, 46, 38, 0.32)')
  horizontal.addColorStop(1, 'rgba(112, 32, 27, 0.03)')
  context.fillStyle = horizontal
  context.fillRect(0, 0, canvas.width, canvas.height)

  const vertical = context.createLinearGradient(0, 0, 0, canvas.height)
  vertical.addColorStop(0, 'rgba(245, 157, 151, 0.62)')
  vertical.addColorStop(0.42, 'rgba(201, 59, 48, 0.26)')
  vertical.addColorStop(1, 'rgba(64, 20, 17, 0)')
  context.fillStyle = vertical
  context.fillRect(0, 0, canvas.width, canvas.height)

  for (let index = 0; index < 9; index += 1) {
    const x = 46 + index * 52
    context.fillStyle = `rgba(239, 142, 132, ${index % 2 === 0 ? 0.42 : 0.2})`
    context.fillRect(x, 0, index % 3 === 0 ? 5 : 2, canvas.height)
  }

  context.textAlign = 'center'
  context.textBaseline = 'middle'
  context.fillStyle = 'rgba(255, 240, 237, 0.92)'
  context.font = '700 58px Consolas, "SFMono-Regular", monospace'
  context.fillText('INTERVIEW', canvas.width / 2, 224)
  context.fillText('CONTEXT', canvas.width / 2, 292)

  const texture = trackTexture(new THREE.CanvasTexture(canvas))
  texture.colorSpace = THREE.SRGBColorSpace
  texture.needsUpdate = true
  return texture
}

function createFlowTexture() {
  const canvas = document.createElement('canvas')
  canvas.width = 128
  canvas.height = 1024
  const context = canvas.getContext('2d')
  if (!context) throw new Error('Canvas 2D context is unavailable')

  context.clearRect(0, 0, canvas.width, canvas.height)
  const glow = context.createLinearGradient(0, 0, 0, canvas.height)
  glow.addColorStop(0, 'rgba(233, 76, 58, 0)')
  glow.addColorStop(0.32, 'rgba(233, 76, 58, 0.08)')
  glow.addColorStop(0.5, 'rgba(255, 221, 216, 0.92)')
  glow.addColorStop(0.68, 'rgba(233, 76, 58, 0.12)')
  glow.addColorStop(1, 'rgba(233, 76, 58, 0)')
  context.fillStyle = glow
  context.fillRect(0, 0, canvas.width, canvas.height)
  context.fillStyle = 'rgba(239, 142, 132, 0.72)'
  context.fillRect(61, 0, 6, canvas.height)

  const texture = trackTexture(new THREE.CanvasTexture(canvas))
  texture.colorSpace = THREE.SRGBColorSpace
  texture.wrapS = THREE.ClampToEdgeWrapping
  texture.wrapT = THREE.RepeatWrapping
  texture.repeat.set(1, 2.5)
  texture.needsUpdate = true
  return texture
}

function createRadialGlowTexture(color: 'blue' | 'green') {
  const canvas = document.createElement('canvas')
  canvas.width = 256
  canvas.height = 256
  const context = canvas.getContext('2d')
  if (!context) throw new Error('Canvas 2D context is unavailable')

  const gradient = context.createRadialGradient(128, 128, 0, 128, 128, 128)
  if (color === 'green') {
    gradient.addColorStop(0, 'rgba(139, 255, 161, 1)')
    gradient.addColorStop(0.2, 'rgba(94, 232, 122, 0.72)')
    gradient.addColorStop(0.55, 'rgba(71, 204, 99, 0.2)')
  } else {
    gradient.addColorStop(0, 'rgba(255, 237, 234, 1)')
    gradient.addColorStop(0.16, 'rgba(239, 142, 132, 0.84)')
    gradient.addColorStop(0.55, 'rgba(201, 59, 48, 0.22)')
  }
  gradient.addColorStop(1, 'rgba(0, 0, 0, 0)')
  context.fillStyle = gradient
  context.fillRect(0, 0, canvas.width, canvas.height)

  const texture = trackTexture(new THREE.CanvasTexture(canvas))
  texture.colorSpace = THREE.SRGBColorSpace
  texture.needsUpdate = true
  return texture
}

function createArchitecture() {
  if (!scene || !renderer) return

  visualRoot = new THREE.Group()
  visualRoot.position.set(-1.65, 0.14, 0)
  scene.add(visualRoot)

  const maxAnisotropy = Math.min(renderer.capabilities.getMaxAnisotropy(), 8)
  const darkMetal = trackMaterial(new THREE.MeshPhysicalMaterial({
    color: 0x101216,
    metalness: 0.94,
    roughness: 0.23,
    clearcoat: 0.48,
    clearcoatRoughness: 0.2,
    envMapIntensity: 1.25
  }))
  const silverMetal = trackMaterial(new THREE.MeshPhysicalMaterial({
    color: 0xc9c9c5,
    metalness: 0.9,
    roughness: 0.17,
    clearcoat: 0.72,
    clearcoatRoughness: 0.12,
    envMapIntensity: 1.5
  }))
  const moduleGeometry = trackGeometry(new RoundedBoxGeometry(9.6, 0.96, 2.48, 8, 0.2))
  const labelGeometry = trackGeometry(new THREE.PlaneGeometry(9.18, 0.82))
  const edgeGeometry = trackGeometry(new THREE.PlaneGeometry(8.88, 0.025))
  const edgeMaterial = trackMaterial(new THREE.MeshBasicMaterial({
    color: 0xffffff,
    transparent: true,
    opacity: 0.2,
    depthWrite: false,
    toneMapped: false
  }))

  const blueGlowTexture = createRadialGlowTexture('blue')
  const greenGlowTexture = createRadialGlowTexture('green')

  const contextTexture = createContextTexture()
  contextMaterial = trackMaterial(new THREE.MeshBasicMaterial({
    map: contextTexture,
    color: 0xf0a39b,
    transparent: true,
    opacity: 0.48,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    toneMapped: false
  }))
  const contextColumn = new THREE.Mesh(
    trackGeometry(new THREE.PlaneGeometry(2.25, 8.65)),
    contextMaterial
  )
  contextColumn.position.set(0, 0.58, -1.32)
  visualRoot.add(contextColumn)

  flowTexture = createFlowTexture()
  const flowMaterial = trackMaterial(new THREE.MeshBasicMaterial({
    map: flowTexture,
    color: 0xf4bbb5,
    transparent: true,
    opacity: 0.7,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    toneMapped: false
  }))
  const flowPlane = new THREE.Mesh(trackGeometry(new THREE.PlaneGeometry(1.1, 7.55)), flowMaterial)
  flowPlane.position.set(0, 0.08, -1.25)
  visualRoot.add(flowPlane)

  stages.forEach((stage, index) => {
    const stageGroup = new THREE.Group()
    stageGroup.position.set((index - 2) * -0.025, 2.72 - index * 1.3, 0)

    const module = new THREE.Mesh(moduleGeometry, stage.light ? silverMetal : darkMetal)
    module.castShadow = true
    module.receiveShadow = true
    stageGroup.add(module)

    const labelTexture = createStageTexture(stage, index, maxAnisotropy)
    const labelMaterial = trackMaterial(new THREE.MeshBasicMaterial({
      map: labelTexture,
      transparent: true,
      alphaTest: 0.02,
      depthWrite: false,
      toneMapped: false
    }))
    const label = new THREE.Mesh(labelGeometry, labelMaterial)
    label.position.set(-0.05, -0.015, 1.25)
    label.renderOrder = 3
    stageGroup.add(label)

    const edge = new THREE.Mesh(edgeGeometry, edgeMaterial)
    edge.position.set(-0.08, 0.457, 1.255)
    edge.renderOrder = 4
    stageGroup.add(edge)

    visualRoot?.add(stageGroup)

    if (index < stages.length - 1) {
      const glowMaterial = trackMaterial(new THREE.SpriteMaterial({
        map: blueGlowTexture,
        color: index === 2 ? 0xef8e84 : 0xe94c3a,
        transparent: true,
        opacity: 0.26,
        blending: THREE.AdditiveBlending,
        depthWrite: false,
        toneMapped: false
      }))
      const gapGlow = new THREE.Sprite(glowMaterial)
      gapGlow.position.set(-0.05, stageGroup.position.y - 0.66, -0.08)
      gapGlow.scale.set(4.6, 0.72, 1)
      gapGlowMaterials.push(glowMaterial)
      visualRoot?.add(gapGlow)
    }
  })

  for (let index = 0; index < 3; index += 1) {
    const material = trackMaterial(new THREE.SpriteMaterial({
      map: blueGlowTexture,
      color: index === 1 ? 0xf0a39b : 0xc93b30,
      transparent: true,
      opacity: 0.75,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
      toneMapped: false
    }))
    const sprite = new THREE.Sprite(material)
    sprite.position.set(0, 4.1 - index * 2.4, -0.12)
    sprite.scale.set(1.15, 0.38, 1)
    visualRoot.add(sprite)
    energyPackets.push({ sprite, material, offset: index / 3, speed: 0.09 + index * 0.012 })
  }

  const baseMetal = trackMaterial(new THREE.MeshPhysicalMaterial({
    color: 0x07090c,
    metalness: 0.72,
    roughness: 0.48,
    clearcoat: 0.14,
    envMapIntensity: 0.38
  }))
  const base = new THREE.Mesh(
    trackGeometry(new THREE.CylinderGeometry(4.36, 4.08, 0.54, 96, 1, false)),
    baseMetal
  )
  base.position.y = -4.08
  base.castShadow = true
  base.receiveShadow = true
  visualRoot.add(base)

  const topPlate = new THREE.Mesh(
    trackGeometry(new THREE.CylinderGeometry(3.78, 4.02, 0.15, 96, 1, false)),
    baseMetal
  )
  topPlate.position.y = -3.7
  topPlate.castShadow = true
  topPlate.receiveShadow = true
  visualRoot.add(topPlate)

  const ringMaterial = trackMaterial(new THREE.MeshBasicMaterial({
    color: 0x6ce485,
    transparent: true,
    opacity: 0.68,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    toneMapped: false
  }))
  energyRing = new THREE.Mesh(trackGeometry(new THREE.TorusGeometry(1.48, 0.022, 10, 96)), ringMaterial)
  energyRing.rotation.x = Math.PI / 2
  energyRing.position.y = -3.55
  visualRoot.add(energyRing)

  const emitterGeometry = trackGeometry(new THREE.ConeGeometry(1.4, 1.34, 48, 8, true))
  emitterMaterial = trackMaterial(new THREE.MeshBasicMaterial({
    color: 0x55dd75,
    transparent: true,
    opacity: 0.2,
    side: THREE.DoubleSide,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    toneMapped: false
  }))
  const emitter = new THREE.Mesh(emitterGeometry, emitterMaterial)
  emitter.position.set(0, -3.0, 0.18)
  visualRoot.add(emitter)

  emitterWireMaterial = trackMaterial(new THREE.MeshBasicMaterial({
    color: 0x70f08b,
    transparent: true,
    opacity: 0.5,
    wireframe: true,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    toneMapped: false
  }))
  const emitterWire = new THREE.Mesh(emitterGeometry, emitterWireMaterial)
  emitterWire.position.copy(emitter.position)
  visualRoot.add(emitterWire)

  for (let index = 1; index <= 4; index += 1) {
    const radius = 0.24 + index * 0.25
    const ring = new THREE.Mesh(
      trackGeometry(new THREE.TorusGeometry(radius, 0.009, 5, 64)),
      ringMaterial
    )
    ring.rotation.x = Math.PI / 2
    ring.position.y = -2.5 - index * 0.22
    visualRoot.add(ring)
  }

  const greenCore = new THREE.Sprite(trackMaterial(new THREE.SpriteMaterial({
    map: greenGlowTexture,
    color: 0x67e780,
    transparent: true,
    opacity: 0.62,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    toneMapped: false
  })))
  greenCore.position.set(0, -3.28, 0.02)
  greenCore.scale.set(2.8, 1.25, 1)
  visualRoot.add(greenCore)

  const groundGlow = new THREE.Sprite(trackMaterial(new THREE.SpriteMaterial({
    map: blueGlowTexture,
    color: 0xc93b30,
    transparent: true,
    opacity: 0.16,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    toneMapped: false
  })))
  groundGlow.position.set(0, -4.3, -0.4)
  groundGlow.scale.set(9.4, 2.1, 1)
  visualRoot.add(groundGlow)
}

function createLights() {
  if (!scene) return
  scene.add(new THREE.HemisphereLight(0xf7dfda, 0x090a0d, 1.25))

  const keyLight = new THREE.DirectionalLight(0xfff2d9, 3.9)
  keyLight.position.set(7, 8, 10)
  keyLight.castShadow = true
  keyLight.shadow.mapSize.set(1024, 1024)
  keyLight.shadow.camera.near = 1
  keyLight.shadow.camera.far = 35
  keyLight.shadow.camera.left = -8
  keyLight.shadow.camera.right = 8
  keyLight.shadow.camera.top = 8
  keyLight.shadow.camera.bottom = -8
  keyLight.shadow.bias = -0.0008
  scene.add(keyLight)

  const rimLight = new THREE.PointLight(0xffdad4, 85, 28, 2)
  rimLight.position.set(7.5, 2.4, -4.5)
  scene.add(rimLight)

  const violetLight = new THREE.PointLight(0xe94c3a, 72, 18, 2)
  violetLight.position.set(-2.4, 2.8, 2.4)
  scene.add(violetLight)

  bluePulseLight = new THREE.PointLight(0xc93b30, 48, 10, 2)
  bluePulseLight.position.set(0, 1.5, 0.6)
  scene.add(bluePulseLight)

  greenLight = new THREE.PointLight(0x63e07d, 30, 9, 2)
  greenLight.position.set(0, -3.15, 1.2)
  scene.add(greenLight)
}

function renderScene(timestamp: number) {
  if (!renderer || !scene || !camera || !visualRoot) return
  const elapsed = timestamp / 1000
  lastTimestamp = timestamp

  pointerCurrent.lerp(pointerTarget, reduceMotion ? 1 : 0.045)
  camera.position.set(
    cameraBase.x + pointerCurrent.x * 0.42 + (reduceMotion ? 0 : Math.sin(elapsed * 0.24) * 0.08),
    cameraBase.y + pointerCurrent.y * 0.18 + (reduceMotion ? 0 : Math.sin(elapsed * 0.19) * 0.05),
    cameraBase.z
  )
  camera.lookAt(
    cameraTarget.x + pointerCurrent.x * 0.12,
    cameraTarget.y + pointerCurrent.y * 0.08,
    cameraTarget.z
  )

  if (!reduceMotion) {
    energyPackets.forEach((packet, index) => {
      const progress = (elapsed * packet.speed + packet.offset) % 1
      packet.sprite.position.y = 4.35 - progress * 7.55
      packet.sprite.position.x = Math.sin(elapsed * 0.75 + index * 2.2) * 0.16
      packet.material.opacity = Math.sin(progress * Math.PI) * 0.76
      const stretch = 0.82 + Math.sin(progress * Math.PI) * 0.55
      packet.sprite.scale.set(1.05 * stretch, 0.3 + stretch * 0.14, 1)
    })

    gapGlowMaterials.forEach((material, index) => {
      material.opacity = 0.2 + Math.max(0, Math.sin(elapsed * 1.45 - index * 0.82)) * 0.2
    })

    if (flowTexture) flowTexture.offset.y = -(elapsed * 0.18) % 1
    if (contextMaterial) contextMaterial.opacity = 0.43 + Math.sin(elapsed * 0.65) * 0.05
    if (emitterMaterial) emitterMaterial.opacity = 0.18 + Math.sin(elapsed * 1.7) * 0.05
    if (emitterWireMaterial) emitterWireMaterial.opacity = 0.42 + Math.sin(elapsed * 1.7) * 0.1
    if (energyRing) {
      const ringScale = 0.98 + Math.sin(elapsed * 1.7) * 0.025
      energyRing.scale.setScalar(ringScale)
    }
    if (greenLight) greenLight.intensity = 26 + Math.sin(elapsed * 1.7) * 7
    if (bluePulseLight) {
      bluePulseLight.position.y = energyPackets[0]?.sprite.position.y ?? 1.5
      bluePulseLight.intensity = 35 + Math.max(0, Math.sin(elapsed * 1.2)) * 24
    }
  }

  renderer.render(scene, camera)
  if (!ready.value) ready.value = true
  if (hostRef.value) hostRef.value.dataset.motionFrame = Math.floor(timestamp / 250).toString()
}

function animate(timestamp: number) {
  renderScene(timestamp)
  animationFrame = window.requestAnimationFrame(animate)
}

function startAnimation() {
  if (reduceMotion || animationFrame) return
  animationFrame = window.requestAnimationFrame(animate)
}

function stopAnimation() {
  if (!animationFrame) return
  window.cancelAnimationFrame(animationFrame)
  animationFrame = 0
}

function resizeScene() {
  const host = hostRef.value
  if (!host || !renderer || !camera || !visualRoot) return
  const width = Math.max(1, Math.round(host.clientWidth))
  const height = Math.max(1, Math.round(host.clientHeight))
  const aspect = width / height
  const compact = width < 520
  const narrowAdjustment = Math.max(0, 1.12 - aspect)
  const wideViewportProgress = THREE.MathUtils.clamp((window.innerWidth - 1480) / 440, 0, 1)
  const framingShift = THREE.MathUtils.lerp(2.5, -1.65, wideViewportProgress)

  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, compact ? 1.4 : 1.75))
  renderer.setSize(width, height, false)
  camera.aspect = aspect
  camera.fov = compact ? 32 : 30
  cameraTarget.x = compact ? 0 : framingShift
  cameraBase.set(
    compact ? 5.45 : 6.9 + framingShift,
    compact ? 0.62 : 0.7,
    (compact ? 18.9 : 22.3) + narrowAdjustment * 4.2
  )
  camera.updateProjectionMatrix()
  visualRoot.scale.setScalar(compact ? 0.93 : 1)

  if (reduceMotion) renderScene(lastTimestamp)
}

function handlePointerMove(event: PointerEvent) {
  const host = hostRef.value
  if (!host || reduceMotion) return
  const bounds = host.getBoundingClientRect()
  const inside = event.clientX >= bounds.left
    && event.clientX <= bounds.right
    && event.clientY >= bounds.top
    && event.clientY <= bounds.bottom
  if (!inside) {
    pointerTarget.set(0, 0)
    return
  }
  pointerTarget.set(
    ((event.clientX - bounds.left) / bounds.width - 0.5) * 2,
    -((event.clientY - bounds.top) / bounds.height - 0.5) * 2
  )
}

function handleMotionChange(event: MediaQueryListEvent) {
  reduceMotion = event.matches
  pointerTarget.set(0, 0)
  if (reduceMotion) {
    stopAnimation()
    renderScene(lastTimestamp)
    return
  }
  startAnimation()
}

function handleContextLost(event: Event) {
  event.preventDefault()
  stopAnimation()
  ready.value = false
  renderFailed.value = true
}

function initializeScene() {
  const host = hostRef.value
  if (!host) return

  try {
    scene = new THREE.Scene()
    renderer = new THREE.WebGLRenderer({
      alpha: true,
      antialias: true,
      powerPreference: 'high-performance'
    })
    renderer.outputColorSpace = THREE.SRGBColorSpace
    renderer.toneMapping = THREE.ACESFilmicToneMapping
    renderer.toneMappingExposure = 1.32
    renderer.shadowMap.enabled = true
    renderer.shadowMap.type = THREE.PCFShadowMap
    renderer.setClearColor(0x000000, 0)
    renderer.domElement.className = 'training-architecture-canvas'
    renderer.domElement.setAttribute('aria-hidden', 'true')
    renderer.domElement.addEventListener('webglcontextlost', handleContextLost)
    host.appendChild(renderer.domElement)

    camera = new THREE.PerspectiveCamera(30, 1, 0.1, 80)
    camera.position.copy(cameraBase)
    camera.lookAt(cameraTarget)

    const pmremGenerator = new THREE.PMREMGenerator(renderer)
    environmentTarget = pmremGenerator.fromScene(new RoomEnvironment(), 0.04)
    scene.environment = environmentTarget.texture
    pmremGenerator.dispose()

    createLights()
    createArchitecture()

    motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
    reduceMotion = motionQuery.matches
    motionQuery.addEventListener('change', handleMotionChange)
    window.addEventListener('pointermove', handlePointerMove, { passive: true })

    resizeObserver = new ResizeObserver(resizeScene)
    resizeObserver.observe(host)
    resizeScene()
    renderScene(0)
    startAnimation()
  } catch (error) {
    console.error('Unable to initialize the training architecture scene', error)
    renderFailed.value = true
  }
}

function disposeScene() {
  stopAnimation()
  resizeObserver?.disconnect()
  resizeObserver = null
  motionQuery?.removeEventListener('change', handleMotionChange)
  motionQuery = null
  window.removeEventListener('pointermove', handlePointerMove)

  if (renderer) {
    renderer.domElement.removeEventListener('webglcontextlost', handleContextLost)
  }

  geometries.forEach((geometry) => geometry.dispose())
  materials.forEach((material) => material.dispose())
  textures.forEach((texture) => texture.dispose())
  geometries.clear()
  materials.clear()
  textures.clear()

  environmentTarget?.dispose()
  environmentTarget = null
  scene?.clear()

  if (renderer) {
    renderer.renderLists.dispose()
    renderer.dispose()
    renderer.forceContextLoss()
    renderer.domElement.remove()
  }

  renderer = null
  scene = null
  camera = null
  visualRoot = null
  flowTexture = null
  contextMaterial = null
  emitterMaterial = null
  emitterWireMaterial = null
  energyRing = null
  greenLight = null
  bluePulseLight = null
  energyPackets.length = 0
  gapGlowMaterials.length = 0
}

onMounted(initializeScene)
onBeforeUnmount(disposeScene)
</script>

<style scoped>
.training-architecture-scene {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 320px;
  overflow: hidden;
  contain: layout paint;
}

.training-architecture-scene::after {
  display: none;
}

.training-architecture-scene :deep(.training-architecture-canvas) {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: block;
  width: 100%;
  height: 100%;
}

.scene-loader,
.scene-fallback {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.scene-loader {
  gap: 7px;
}

.scene-loader span {
  width: 4px;
  height: 32px;
  background: var(--color-brand-500);
  box-shadow: 0 0 16px rgba(233, 76, 58, 0.46);
  animation: scene-loading 900ms ease-in-out infinite alternate;
}

.scene-loader span:nth-child(2) {
  height: 54px;
  animation-delay: -300ms;
}

.scene-loader span:nth-child(3) {
  animation-delay: -600ms;
}

.fallback-stack {
  display: grid;
  width: min(86%, 560px);
  gap: 12px;
  transform: perspective(900px) rotateY(-10deg);
}

.fallback-stack span {
  height: 56px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 6px;
  background: #242421;
}

.fallback-stack span:nth-last-child(-n + 2) {
  background: #deddd8;
}

@keyframes scene-loading {
  from { opacity: 0.24; transform: scaleY(0.65); }
  to { opacity: 0.92; transform: scaleY(1); }
}

@media (prefers-reduced-motion: reduce) {
  .scene-loader span {
    animation: none;
  }
}
</style>
