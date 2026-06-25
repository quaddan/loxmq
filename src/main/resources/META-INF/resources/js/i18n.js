/*
 * loxmq — i18n module
 *
 * Vanilla JS, no deps. Self-contained.
 *
 * Architecture :
 *   - Static dictionary I18N[lang][key] = translation
 *   - Détection initiale : localStorage('lang') > navigator.language > 'en'
 *   - HTML markup :
 *       data-i18n="key"           → element.textContent
 *       data-i18n-html="key"      → element.innerHTML (use sparingly)
 *       data-i18n-attr="attr1:key1,attr2:key2"  → setAttribute
 *       data-i18n-switcher        → wired as language <select> at boot
 *       data-i18n-page-title="key" sur <html>   → document.title
 *   - JS dynamic strings :
 *       t('key')              → simple lookup
 *       t('key', {name: 'x'}) → interpolation {name} placeholders
 *   - Pages dynamiques :
 *       window.addEventListener('i18n:changed', function (ev) { ... });
 *       Re-render le contenu généré par JS dans le handler.
 *
 * Langues : fr / en / de. Fallback en cas de clé manquante : 'en' puis
 * la clé brute (visibilité pour debug).
 *
 * Runtime data (entry names, room/cat names, UUIDs, JSON values) :
 * jamais traduit — ces strings viennent du Miniserver / LoxAPP3 et
 * appartiennent à l'opérateur final.
 */
(function (window) {
    'use strict';

    var DEFAULT_LANG = 'en';
    var SUPPORTED   = ['fr', 'en', 'de'];

    // ============================================================
    //  DICTIONNAIRE
    //  Convention de clés : namespace.context.key (dot notation).
    //  Interpolation : {name}, {count}, {uuid}, … remplacés par
    //  params à l'appel de t(key, params).
    // ============================================================
    var I18N = {
        fr: {
            // ---- meta ----
            'app.name':              'LoxMQ',
            'lang.label':            'Langue',
            'lang.fr':               'Français',
            'lang.en':               'English',
            'lang.de':               'Deutsch',

            // ---- navigation ----
            'nav.dashboard':         'Tableau de bord',
            'nav.states':            'États en direct',
            'nav.schedules':         'Plannings',
            'nav.users':             'Utilisateurs',
            'nav.logs':              'Journaux',

            // ---- common ----
            'common.refresh':        'Rafraîchir',
            'common.refreshNow':     'Rafraîchir maintenant',
            'common.refreshAuto30':  'Rafraîchissement automatique toutes les 30s',
            'common.auto30s':        'auto 30s',
            'common.add':            'Ajouter',
            'common.edit':           'Modifier',
            'common.delete':         'Supprimer',
            'common.cancel':         'Annuler',
            'common.save':           'Enregistrer',
            'common.saveChanges':    'Enregistrer',
            'common.close':          'Fermer',
            'common.loading':        'Chargement…',
            'common.search':         'Rechercher',
            'common.filter':         'Filtrer',
            'common.actions':        'Actions',
            'common.name':           'Nom',
            'common.status':         'Statut',
            'common.unknown':        'Inconnu',
            'common.yes':            'Oui',
            'common.no':             'Non',
            'common.view':           'Voir',
            'common.backToDashboard':'← Tableau de bord',

            // ---- footer ----
            'footer.health':         '/q/health',

            // ---- schedules : page ----
            'schedules.subtitle':            '— Plannings',
            'schedules.pageTitle':           'loxmq — Plannings',
            'users.pageTitle':               'loxmq — Utilisateurs',
            'states.pageTitle':              'loxmq — États en direct',
            'dashboard.pageTitle':           'loxmq — Tableau de bord',
            'logs.pageTitle':                'loxmq — Journaux',
            'schedules.add.title':           'Ajouter une entrée de planning',
            'schedules.edit.title':          'Modifier une entrée de planning',
            'schedules.entries.title':       'Entrées',
            'schedules.field.name':          'Nom',
            'schedules.field.opMode':        'Mode opérationnel',
            'schedules.field.calMode':       'Mode calendrier',
            'schedules.placeholder.name':    'ex. Vacances été',

            // ---- schedules : calMode dropdown options ----
            'schedules.calMode.0':           '0 — Date annuelle',
            'schedules.calMode.1':           '1 — Décalage Pâques',
            'schedules.calMode.2':           '2 — Date spécifique',
            'schedules.calMode.3':           '3 — Période spécifique',
            'schedules.calMode.4':           '4 — Période annuelle',
            'schedules.calMode.5':           '5 — Jour de semaine',
            'schedules.calMode.label.0':     'Date annuelle',
            'schedules.calMode.label.1':     'Décalage Pâques',
            'schedules.calMode.label.2':     'Date spécifique',
            'schedules.calMode.label.3':     'Période spécifique',
            'schedules.calMode.label.4':     'Période annuelle',
            'schedules.calMode.label.5':     'Jour de semaine',

            // ---- schedules : mois (abréviations 3-4 lettres) ----
            'schedules.month.1':  'Jan',  'schedules.month.2':  'Fév',
            'schedules.month.3':  'Mar',  'schedules.month.4':  'Avr',
            'schedules.month.5':  'Mai',  'schedules.month.6':  'Juin',
            'schedules.month.7':  'Juil', 'schedules.month.8':  'Août',
            'schedules.month.9':  'Sep',  'schedules.month.10': 'Oct',
            'schedules.month.11': 'Nov',  'schedules.month.12': 'Déc',

            // ---- schedules : jours de semaine (Loxone 0=Lun..6=Dim) ----
            'schedules.weekday.0': 'Lun', 'schedules.weekday.1': 'Mar',
            'schedules.weekday.2': 'Mer', 'schedules.weekday.3': 'Jeu',
            'schedules.weekday.4': 'Ven', 'schedules.weekday.5': 'Sam',
            'schedules.weekday.6': 'Dim',

            // ---- schedules : ordinaux d'occurrence (0=chaque, 5=dernier) ----
            'schedules.occurrence.0': 'chaque',  'schedules.occurrence.1': '1er',
            'schedules.occurrence.2': '2e',      'schedules.occurrence.3': '3e',
            'schedules.occurrence.4': '4e',      'schedules.occurrence.5': 'dernier',

            // ---- schedules : calMode 5 conjonctions ----
            'schedules.calMode5.of':    'de',
            'schedules.calMode5.month': 'mois',

            // ---- schedules : calMode 1 préfixe Pâques ----
            'schedules.easter.label':   'Pâques',
            'schedules.easter.dayUnit': 'j',

            // ---- schedules : builder sub-labels ----
            'schedules.builder.day':         'Jour',
            'schedules.builder.month':       'Mois',
            'schedules.builder.date':        'Date',
            'schedules.builder.start':       'Début',
            'schedules.builder.end':         'Fin',
            'schedules.builder.startDay':    'Jour début',
            'schedules.builder.startMonth':  'Mois début',
            'schedules.builder.endDay':      'Jour fin',
            'schedules.builder.endMonth':    'Mois fin',
            'schedules.builder.weekday':     'Jour',
            'schedules.builder.occurrence':  'Occurrence',
            'schedules.builder.easterOffset':'Décalage (jours)',
            'schedules.builder.easterHint':  'ex. -2 = Vendredi Saint, +1 = Lundi de Pâques',
            'schedules.builder.everyMonth':  'Tous les mois',
            'schedules.builder.rawPlaceholder':'Attributs bruts séparés par /',

            // ---- schedules : preview + table ----
            'schedules.preview':             'Envoyé au Miniserver →',
            'schedules.table.name':          'Nom',
            'schedules.table.opMode':        'Mode Op.',
            'schedules.table.calMode':       'Mode Cal.',
            'schedules.table.attrs':         'Attrs',
            'schedules.table.currently':     'Actuellement',

            // ---- schedules : status (computeScheduleStatus) ----
            'schedules.status.activeToday':       'Actif aujourd\'hui',
            'schedules.status.activeTodayOneShot':'Actif aujourd\'hui (unique)',
            'schedules.status.activeTodayEaster': 'Actif aujourd\'hui (Pâques {offset}j)',
            'schedules.status.activeUntil':       'Actif jusqu\'au {date}',
            'schedules.status.activeEvery':       'Actif (chaque {day})',
            'schedules.status.activeNth':         'Actif ({occ} {day})',
            'schedules.status.next':              'Prochain : {date}',
            'schedules.status.nextDay':           'Prochain : {day}',
            'schedules.status.starts':            'Commence le {date}',
            'schedules.status.was':               'Était le {date}',
            'schedules.status.ended':             'Terminé le {date}',
            'schedules.status.notThis':           'Pas ce {occ} {day}',
            'schedules.status.activeInMonth':     'Actif seulement au mois {month}',
            'schedules.status.missingMonthDay':   'Mois/jour manquant',
            'schedules.status.missingDate':       'Date manquante',
            'schedules.status.missingDates':      'Dates manquantes',
            'schedules.status.missingMonthsDays': 'Mois/jours manquants',
            'schedules.status.missingEaster':     'Décalage Pâques manquant',
            'schedules.status.missingWeekday':    'Configuration jour manquante',
            'schedules.status.unknownCalMode':    'calMode {mode} inconnu',

            // ---- schedules : toasts ----
            'schedules.toast.loading':       'Chargement…',
            'schedules.toast.loaded':        '✓ {count} entrées chargées',
            'schedules.toast.creating':      'Création de "{name}"…',
            'schedules.toast.created':       '✓ "{name}" créé',
            'schedules.toast.deleting':      'Suppression de "{name}"…',
            'schedules.toast.deleted':       '✓ "{name}" supprimé',
            'schedules.toast.saving':        'Enregistrement de "{name}"…',
            'schedules.toast.saved':         '✓ Enregistré',
            'schedules.toast.updated':       '✓ "{name}" mis à jour',
            'schedules.toast.editing':       'Modification de "{name}" ({uuid}…)',
            'schedules.toast.notInCache':    '⚠ entrée introuvable dans le cache : {uuid}',
            'schedules.toast.missingUuid':   '⚠ uuid manquant',
            'schedules.toast.missingName':   '⚠ nom requis',
            'schedules.toast.loxApp3':       'LoxAPP3 pas encore chargé — saisir un ID numérique',
            'schedules.confirm.delete':      'Supprimer le planning "{name}" ?',

            // ---- users : page ----
            'users.subtitle':                '— Utilisateurs',
            'users.tab.users':               'Utilisateurs',
            'users.tab.groups':              'Groupes',
            'users.add.user':                'Ajouter un utilisateur',
            'users.add.group':               'Ajouter un groupe',
            'users.edit.user':               'Modifier l\'utilisateur',
            'users.edit.group':              'Modifier le groupe',
            'users.field.name':              'Nom',
            'users.field.fullName':          'Nom complet',
            'users.field.password':          'Mot de passe',
            'users.field.visuPassword':      'Mot de passe visualisation',
            'users.field.accessCode':        'Code d\'accès',
            'users.field.email':             'Email',
            'users.field.userState':         'État',
            'users.field.validFrom':         'Valide à partir de',
            'users.field.validUntil':        'Valide jusqu\'au',
            'users.field.expirationAction':  'Action à expiration',
            'users.field.groups':            'Groupes',
            'users.field.members':           'Membres',
            'users.field.permissions':       'Permissions',
            'users.field.description':       'Description',
            'users.field.validity':          'Validité',
            'users.state.enabled':           'Actif',
            'users.state.disabled':          'Désactivé',
            'users.state.pending':           'En attente',
            'users.state.expired':           'Expiré',
            'users.action.disable':          'Désactiver',
            'users.action.enable':           'Activer',
            'users.action.assignGroup':      'Assigner au groupe',
            'users.action.removeFromGroup':  'Retirer du groupe',
            'users.action.nfc':              'NFC',
            'users.action.changePassword':   'Changer le mot de passe',
            'users.tab.view':                'Voir',
            'users.tab.edit':                'Modifier',
            'users.tab.auth':                'Authentification',
            'users.tab.nfc':                 'NFC',
            'users.toast.loading':           'Chargement…',
            'users.toast.loaded':            '✓ {count} utilisateurs chargés',
            'users.confirm.delete':          'Supprimer l\'utilisateur "{name}" ?',
            'users.confirm.deleteGroup':     'Supprimer le groupe "{name}" ?',

            // ---- states : page ----
            'states.subtitle':               '— États en direct',
            'states.title':                  'États',
            'states.filter.search':          'Rechercher (nom de control, room, cat, UUID)',
            'states.filter.unknown':         'Masquer les controls UNKNOWN',
            'states.col.control':            'Control',
            'states.col.room':               'Room',
            'states.col.cat':                'Catégorie',
            'states.col.state':              'État',
            'states.col.value':              'Valeur',
            'states.col.updated':            'Mis à jour',
            'states.col.sparkline':          'Tendance',
            'states.toast.loading':          'Chargement…',
            'states.toast.loaded':           '✓ {count} états chargés',

            // ---- dashboard : page ----
            'dashboard.subtitle':            '— Tableau de bord',
            'dashboard.tile.miniserver':     'Miniserver',
            'dashboard.tile.mqtt':           'MQTT',
            'dashboard.tile.session':        'Session',
            'dashboard.tile.uptime':         'Uptime',
            'dashboard.tile.version':        'Version',
            'dashboard.tile.heap':           'Heap',
            'dashboard.tile.threads':        'Threads',
            'dashboard.tile.states':         'États',
            'dashboard.tile.commands':       'Commandes',
            'dashboard.status.connected':    'Connecté',
            'dashboard.status.disconnected': 'Déconnecté',
            'dashboard.status.connecting':   'Connexion…',

            // ---- logs : page ----
            'logs.subtitle':                 '— Journaux',
            'logs.file':                     'Fichier',
            'logs.lines':                    'Lignes',
            'logs.follow':                   'Suivre',
            'logs.scrollBottom':             '↓ Bas',
            'logs.toast.loading':            'Chargement…',
            'logs.toast.loaded':             '✓ {count} lignes chargées',

            // ============================================================
            //  Clés complémentaires pour /users /states /logs /dashboard
            //  (couverture intégrale labels visibles).
            // ============================================================

            // ---- users : modal Detail + Create + Group ----
            'users.detail.title':            'Détail utilisateur',
            'users.create.title':            'Créer un utilisateur',
            'users.create.submit':           'Créer',
            'users.group.title':             'Groupe',
            'users.tab.view':                'Voir',
            'users.tab.editMeta':            'Modifier méta',
            'users.tab.groupsTab':           'Groupes',
            'users.tab.authTab':             'Auth',
            'users.tab.nfcTab':              'NFC',
            'users.refresh.auto30':          'Rafraîchissement automatique toutes les 30s (en pause si une modale est ouverte)',
            'users.refresh.now':             'Rafraîchir maintenant',
            'users.refresh.btn':             '↻ Rafraîchir',
            'users.table.name':              'Nom',
            'users.table.admin':             'Admin?',
            'users.table.state':             'État',
            'users.table.validity':          'Validité',
            'users.table.actions':           'Actions',
            'users.table.description':       'Description',
            'users.table.members':           'Membres',
            'users.section.loginPassword':   'Mot de passe de connexion',
            'users.section.visuPassword':    'Mot de passe visualisation',
            'users.section.accessCode':      'Code d\'accès',
            'users.section.assignedTags':    'Tags NFC assignés',
            'users.section.addTag':          'Ajouter un tag NFC',
            'users.section.groups':          'Groupes',
            'users.label.newPassword':       'Nouveau mot de passe',
            'users.label.newVisu':           'Nouveau mot de passe visu',
            'users.label.newAccessCode':     'Nouveau code d\'accès',
            'users.label.tagId':             'ID du tag',
            'users.label.tagName':           'Nom convivial',
            'users.btn.savePassword':        'Enregistrer le mot de passe',
            'users.btn.saveVisu':            'Enregistrer le mot de passe visu',
            'users.btn.saveCode':            'Enregistrer le code d\'accès',
            'users.btn.discover':            'Découvrir (toucher un tag)',
            'users.btn.addTag':              'Ajouter le tag',
            'users.btn.disable':             'Désactiver',
            'users.btn.delete':              'Supprimer',
            'users.btn.viewEdit':            'Voir / Modifier',
            'users.btn.editGroup':           'Modifier',
            'users.btn.deleteGroup':         'Supprimer',
            'users.btn.removeFromGroup':     'Retirer du groupe',
            'users.btn.removeTag':           'Retirer le tag',
            'users.btn.saveChanges':         'Enregistrer',
            'users.btn.saveMetadata':        'Enregistrer',
            'users.nfc.hint':                'Cliquez sur <em>Découvrir</em>, puis présentez la carte NFC à n\'importe quel lecteur autorisé.',
            'users.nfc.placeholder':         'AA:BB:CC:DD ou AABBCCDD',
            'users.option.yes':              'Oui',
            'users.option.no':               'Non',
            'users.badge.admin':             'ADMIN',
            'users.error.label':             'Erreur',

            // STATE_LABEL (user.userState 0-4) + actions d'expiration.
            // Affichés dans la chip "State" du tableau et dans le panneau
            // View du modal Detail.
            'users.stateLabel.0':            'Actif (sans limite)',
            'users.stateLabel.1':            'Désactivé',
            'users.stateLabel.2':            'Actif jusqu\'au',
            'users.stateLabel.3':            'Actif à partir du',
            'users.stateLabel.4':            'Période',
            'users.stateLabel.fallback':     'état {state}',
            'users.expirationAction.0':      'Désactiver',
            'users.expirationAction.1':      'Supprimer',
            'users.noMembers':               'aucun membre',
            'users.noGroups':                'aucun groupe assigné',

            // Colonne Validity + computeUserStatus + snapshot toast.
            'users.validity.noLimit':        'sans limite',
            'users.validity.loading':        'chargement…',
            'users.validity.until':          'jusqu\'au {date}',
            'users.validity.from':           'à partir du {date}',
            'users.validity.range':          '{from} → {to}',
            'users.status.activeNoLimit':    'Actif — sans limite de temps',
            'users.status.disabled':         'Désactivé',
            'users.status.activeExpires':    'Actif — expire le {date}',
            'users.status.expiredSince':     'Expiré depuis le {date}',
            'users.status.activeSince':      'Actif depuis le {date}',
            'users.status.pendingStarts':    'En attente — démarre le {date}',
            'users.status.pendingStartsUntil':'En attente — démarre le {from} (jusqu\'au {to})',
            'users.status.unknownState':     'userState inconnu = {state}',
            'users.status.missingValidUntil2':'validUntil manquant pour state=2',
            'users.status.missingValidFrom3':'validFrom manquant pour state=3',
            'users.status.missingForState4': 'validFrom ou validUntil manquant pour state=4',
            'users.snapshot':                '✓ {users} utilisateurs, {groups} groupes',
            'users.snapshotLoading':         'Chargement…',

            // ---- states : filtres + events + table ----
            'states.filter.title':           'Filtres',
            'states.events.title':           'Événements',
            'states.filter.hideUnknown':     'Masquer',
            'states.filter.clearBtn':        'Vider',
            'states.filter.clearTitle':      'Vide la table (les events à venir continuent d\'arriver)',
            'states.filter.live':            'En direct',
            'states.filter.liveTitle':       'Coché : affiche les events en direct. Décoché : met l\'affichage en pause (les events qui arrivent sont ignorés, pas mémorisés — pas de rattrapage à la reprise).',
            'states.filter.searchPlaceholder':'Rechercher (nom, room, cat, UUID)',
            'states.table.time':             'Heure',
            'states.table.type':             'Type',
            'states.table.room':             'Pièce',
            'states.table.cat':              'Catégorie',
            'states.table.name':             'Nom',
            'states.table.value':            'Valeur',
            'states.table.sparkline':        'Tendance',
            'states.table.sparklineTitle':   'Tendance des derniers points pour ce UUID (~30 derniers events)',
            // Counter row interpolated par updateCounter().
            'states.counter.text':           '{visible} visible / {totalInTable} affiché / {totalReceived} reçus',
            // Placeholders dropdown filtres. FR a 2 genres : masc. pour
            // Type/Nom, fem. pour Pièce/Catégorie. Anglais et allemand
            // utilisent une seule forme commune mais on garde 2 clés pour
            // symétrie d'API.
            'states.filter.allMasc':         '(tous)',
            'states.filter.allFem':          '(toutes)',
            'states.filter.kind.value':      'valeur',
            'states.filter.kind.text':       'texte',

            // ---- logs : page ----
            'logs.section.files':            'Fichiers de journal disponibles',
            'logs.section.viewer':           'Visionneuse',
            'logs.label.minLevel':           'Niveau min',
            'logs.label.minLevelTitle':      'Affiche les sévérités ≥ niveau sélectionné (ex. WARN → WARN + ERROR). Les continuations de stack trace héritent du niveau du parent.',
            'logs.label.lines':              'Lignes',
            'logs.label.file':               'Fichier',
            'logs.label.follow':             'Suivre la fin',
            'logs.btn.refresh':              'Rafraîchir',

            // ---- dashboard : panels + dt labels + actions ----
            'dashboard.panel.miniserver':    'Miniserver',
            'dashboard.panel.mqtt':          'MQTT',
            'dashboard.panel.session':       'Session',
            'dashboard.panel.connection':    'Connexion',
            'dashboard.panel.identity':      'Identité',
            'dashboard.panel.token':         'Token',
            'dashboard.panel.bootstrap':     'Bootstrap',
            'dashboard.panel.keepalive':     'KeepAlive',
            'dashboard.panel.actions':       'Actions',
            'dashboard.panel.metrics':       'Métriques',
            'dashboard.panel.transport':     'Transport',
            'dashboard.dt.host':             'Hôte',
            'dashboard.dt.port':             'Port',
            'dashboard.dt.uuid':             'UUID',
            'dashboard.dt.permission':       'Permission',
            'dashboard.dt.preferred':        'Préféré',
            'dashboard.dt.effective':        'Effectif',
            'dashboard.dt.webSocket':        'WebSocket',
            'dashboard.dt.downgrade':        'Rétrogradation',
            'dashboard.dt.session':          'Session',
            'dashboard.dt.serial':           'Numéro de série',
            'dashboard.dt.version':          'Version',
            'dashboard.dt.generation':       'Génération',
            'dashboard.dt.https':            'HTTPS',
            'dashboard.dt.address':          'Adresse',
            'dashboard.dt.local':            'Local',
            'dashboard.dt.sdcard':           'Carte SD',
            'dashboard.sdcard.ok':           'OK',
            'dashboard.sdcard.error':        'ERREUR',
            'dashboard.sdcard.pending':      'EN ATTENTE',
            'dashboard.dt.status':           'Statut',
            'dashboard.dt.expiresAt':        'Expire le',
            'dashboard.dt.nextRefresh':      'Prochain refresh',
            'dashboard.fw.upToDate':         'à jour',
            'dashboard.fw.updateAvailable':  'mise à jour dispo',
            'dashboard.dt.rights':           'Droits',
            'dashboard.dt.keepalive':        'KeepAlive',
            'dashboard.dt.lastHandshake':    'Dernier handshake',
            'dashboard.dt.metrics':          'Métriques',
            'dashboard.dt.started':          'Démarré',
            'dashboard.dt.completed':        'Terminé',
            'dashboard.dt.duration':         'Durée',
            'dashboard.dt.lastError':        'Dernière erreur',
            'dashboard.dt.protocol':         'Protocole',
            'dashboard.btn.connect':         'Connecter au Miniserver',
            'dashboard.btn.disconnect':      'Déconnecter du Miniserver',
            'dashboard.btn.reconnect':       'Forcer la reconnexion',
            'dashboard.btn.refreshToken':    'Rafraîchir le token',
            'dashboard.btn.killToken':       'Supprimer le token',
            'dashboard.btn.reboot':          'Redémarrer le Miniserver',
            'dashboard.btn.rebootConfirm':   'Redémarrer le Miniserver ? Toutes les connexions (WebSocket, états en direct) seront coupées pendant ~30 à 60 s, le temps du redémarrage.',
            'dashboard.tag.noToken':         'AUCUN TOKEN',
            'dashboard.tag.unresolved':      '— bootstrap (jdev/cfg/apiKey) pas encore arrivé.',
            'dashboard.tag.connectFirst':    '— connectez-vous d\'abord pour obtenir un token.',
            'dashboard.tag.armed':           'ARMÉ — envoi toutes les',
            'dashboard.tag.idle':            'INACTIF — non planifié (pas encore de session RUNNING)',
            // dt labels MQTT + KeepAlive + boutons MQTT actions.
            'dashboard.dt.path':             'Chemin',
            'dashboard.dt.clientId':         'ID client',
            'dashboard.dt.mode':             'Mode',
            'dashboard.dt.qos':              'QoS',
            'dashboard.dt.lastRtt':          'Dernier RTT',
            'dashboard.meta.noResponse':     'pas encore de réponse',
            'dashboard.meta.noHandshake':    'pas encore atteint RUNNING',
            'dashboard.btn.connectMqtt':     'Connecter au broker MQTT',
            'dashboard.btn.disconnectMqtt':  'Déconnecter du broker MQTT'
        },

        en: {
            // ---- meta ----
            'app.name':              'LoxMQ',
            'lang.label':            'Language',
            'lang.fr':               'Français',
            'lang.en':               'English',
            'lang.de':               'Deutsch',

            // ---- navigation ----
            'nav.dashboard':         'Dashboard',
            'nav.states':            'Live states',
            'nav.schedules':         'Schedules',
            'nav.users':             'Users',
            'nav.logs':              'Logs',

            // ---- common ----
            'common.refresh':        'Refresh',
            'common.refreshNow':     'Refresh now',
            'common.refreshAuto30':  'Auto-refresh every 30s',
            'common.auto30s':        'auto 30s',
            'common.add':            'Add',
            'common.edit':           'Edit',
            'common.delete':         'Delete',
            'common.cancel':         'Cancel',
            'common.save':           'Save',
            'common.saveChanges':    'Save changes',
            'common.close':          'Close',
            'common.loading':        'Loading…',
            'common.search':         'Search',
            'common.filter':         'Filter',
            'common.actions':        'Actions',
            'common.name':           'Name',
            'common.status':         'Status',
            'common.unknown':        'Unknown',
            'common.yes':            'Yes',
            'common.no':             'No',
            'common.view':           'View',
            'common.backToDashboard':'← Dashboard',

            'footer.health':         '/q/health',

            // ---- schedules ----
            'schedules.subtitle':            '— Schedules',
            'schedules.pageTitle':           'loxmq — Schedules',
            'users.pageTitle':               'loxmq — Users',
            'states.pageTitle':              'loxmq — Live states',
            'dashboard.pageTitle':           'loxmq — Dashboard',
            'logs.pageTitle':                'loxmq — Logs',
            'schedules.add.title':           'Add a schedule entry',
            'schedules.edit.title':          'Edit schedule entry',
            'schedules.entries.title':       'Entries',
            'schedules.field.name':          'Name',
            'schedules.field.opMode':        'Operating mode',
            'schedules.field.calMode':       'Calendar mode',
            'schedules.placeholder.name':    'e.g. Summer vacation',

            'schedules.calMode.0':           '0 — Yearly date',
            'schedules.calMode.1':           '1 — Easter offset',
            'schedules.calMode.2':           '2 — Specific date',
            'schedules.calMode.3':           '3 — Specific timespan',
            'schedules.calMode.4':           '4 — Yearly timespan',
            'schedules.calMode.5':           '5 — Weekday',
            'schedules.calMode.label.0':     'Yearly date',
            'schedules.calMode.label.1':     'Easter offset',
            'schedules.calMode.label.2':     'Specific date',
            'schedules.calMode.label.3':     'Specific timespan',
            'schedules.calMode.label.4':     'Yearly timespan',
            'schedules.calMode.label.5':     'Weekday',

            'schedules.month.1':  'Jan',  'schedules.month.2':  'Feb',
            'schedules.month.3':  'Mar',  'schedules.month.4':  'Apr',
            'schedules.month.5':  'May',  'schedules.month.6':  'Jun',
            'schedules.month.7':  'Jul',  'schedules.month.8':  'Aug',
            'schedules.month.9':  'Sep',  'schedules.month.10': 'Oct',
            'schedules.month.11': 'Nov',  'schedules.month.12': 'Dec',

            'schedules.weekday.0': 'Mon', 'schedules.weekday.1': 'Tue',
            'schedules.weekday.2': 'Wed', 'schedules.weekday.3': 'Thu',
            'schedules.weekday.4': 'Fri', 'schedules.weekday.5': 'Sat',
            'schedules.weekday.6': 'Sun',

            'schedules.occurrence.0': 'every', 'schedules.occurrence.1': '1st',
            'schedules.occurrence.2': '2nd',   'schedules.occurrence.3': '3rd',
            'schedules.occurrence.4': '4th',   'schedules.occurrence.5': 'last',

            'schedules.calMode5.of':    'of',
            'schedules.calMode5.month': 'month',

            'schedules.easter.label':   'Easter',
            'schedules.easter.dayUnit': 'd',

            'schedules.builder.day':         'Day',
            'schedules.builder.month':       'Month',
            'schedules.builder.date':        'Date',
            'schedules.builder.start':       'Start',
            'schedules.builder.end':         'End',
            'schedules.builder.startDay':    'Start day',
            'schedules.builder.startMonth':  'Start month',
            'schedules.builder.endDay':      'End day',
            'schedules.builder.endMonth':    'End month',
            'schedules.builder.weekday':     'Weekday',
            'schedules.builder.occurrence':  'Occurrence',
            'schedules.builder.easterOffset':'Offset (days)',
            'schedules.builder.easterHint':  'e.g. -2 = Good Friday, +1 = Easter Monday',
            'schedules.builder.everyMonth':  'Every month',
            'schedules.builder.rawPlaceholder':'Raw slash-delimited attrs',

            'schedules.preview':             'Sent to Miniserver →',
            'schedules.table.name':          'Name',
            'schedules.table.opMode':        'Op Mode',
            'schedules.table.calMode':       'Cal Mode',
            'schedules.table.attrs':         'Attrs',
            'schedules.table.currently':     'Currently',

            'schedules.status.activeToday':       'Active today',
            'schedules.status.activeTodayOneShot':'Active today (one-shot)',
            'schedules.status.activeTodayEaster': 'Active today (Easter {offset}d)',
            'schedules.status.activeUntil':       'Active until {date}',
            'schedules.status.activeEvery':       'Active (every {day})',
            'schedules.status.activeNth':         'Active ({occ} {day})',
            'schedules.status.next':              'Next: {date}',
            'schedules.status.nextDay':           'Next: {day}',
            'schedules.status.starts':            'Starts {date}',
            'schedules.status.was':               'Was {date}',
            'schedules.status.ended':             'Ended {date}',
            'schedules.status.notThis':           'Not this {occ} {day}',
            'schedules.status.activeInMonth':     'Active only in month {month}',
            'schedules.status.missingMonthDay':   'Missing month/day',
            'schedules.status.missingDate':       'Missing date',
            'schedules.status.missingDates':      'Missing dates',
            'schedules.status.missingMonthsDays': 'Missing months/days',
            'schedules.status.missingEaster':     'Missing easter offset',
            'schedules.status.missingWeekday':    'Missing weekday config',
            'schedules.status.unknownCalMode':    'Unknown calMode {mode}',

            'schedules.toast.loading':       'Loading…',
            'schedules.toast.loaded':        '✓ {count} entries loaded',
            'schedules.toast.creating':      'Creating "{name}"…',
            'schedules.toast.created':       '✓ Created "{name}"',
            'schedules.toast.deleting':      'Deleting "{name}"…',
            'schedules.toast.deleted':       '✓ Deleted "{name}"',
            'schedules.toast.saving':        'Saving "{name}"…',
            'schedules.toast.saved':         '✓ Saved',
            'schedules.toast.updated':       '✓ Updated "{name}"',
            'schedules.toast.editing':       'Editing "{name}" ({uuid}…)',
            'schedules.toast.notInCache':    '⚠ entry not found in cache: {uuid}',
            'schedules.toast.missingUuid':   '⚠ missing uuid',
            'schedules.toast.missingName':   '⚠ name required',
            'schedules.toast.loxApp3':       'LoxAPP3 not yet loaded — type a numeric ID',
            'schedules.confirm.delete':      'Delete schedule "{name}" ?',

            // ---- users ----
            'users.subtitle':                '— Users',
            'users.tab.users':               'Users',
            'users.tab.groups':              'Groups',
            'users.add.user':                'Add user',
            'users.add.group':               'Add group',
            'users.edit.user':               'Edit user',
            'users.edit.group':              'Edit group',
            'users.field.name':              'Name',
            'users.field.fullName':          'Full name',
            'users.field.password':          'Password',
            'users.field.visuPassword':      'Visualization password',
            'users.field.accessCode':        'Access code',
            'users.field.email':             'Email',
            'users.field.userState':         'State',
            'users.field.validFrom':         'Valid from',
            'users.field.validUntil':        'Valid until',
            'users.field.expirationAction':  'Expiration action',
            'users.field.groups':            'Groups',
            'users.field.members':           'Members',
            'users.field.permissions':       'Permissions',
            'users.field.description':       'Description',
            'users.field.validity':          'Validity',
            'users.state.enabled':           'Active',
            'users.state.disabled':          'Disabled',
            'users.state.pending':           'Pending',
            'users.state.expired':           'Expired',
            'users.action.disable':          'Disable',
            'users.action.enable':           'Enable',
            'users.action.assignGroup':     'Assign to group',
            'users.action.removeFromGroup': 'Remove from group',
            'users.action.nfc':              'NFC',
            'users.action.changePassword':   'Change password',
            'users.tab.view':                'View',
            'users.tab.edit':                'Edit',
            'users.tab.auth':                'Auth',
            'users.tab.nfc':                 'NFC',
            'users.toast.loading':           'Loading…',
            'users.toast.loaded':            '✓ {count} users loaded',
            'users.confirm.delete':          'Delete user "{name}" ?',
            'users.confirm.deleteGroup':     'Delete group "{name}" ?',

            // ---- states ----
            'states.subtitle':               '— Live states',
            'states.title':                  'States',
            'states.filter.search':          'Search (control name, room, cat, UUID)',
            'states.filter.unknown':         'Hide UNKNOWN controls',
            'states.col.control':            'Control',
            'states.col.room':               'Room',
            'states.col.cat':                'Category',
            'states.col.state':              'State',
            'states.col.value':              'Value',
            'states.col.updated':            'Updated',
            'states.col.sparkline':          'Trend',
            'states.toast.loading':          'Loading…',
            'states.toast.loaded':           '✓ {count} states loaded',

            // ---- dashboard ----
            'dashboard.subtitle':            '— Dashboard',
            'dashboard.tile.miniserver':     'Miniserver',
            'dashboard.tile.mqtt':           'MQTT',
            'dashboard.tile.session':        'Session',
            'dashboard.tile.uptime':         'Uptime',
            'dashboard.tile.version':        'Version',
            'dashboard.tile.heap':           'Heap',
            'dashboard.tile.threads':        'Threads',
            'dashboard.tile.states':         'States',
            'dashboard.tile.commands':       'Commands',
            'dashboard.status.connected':    'Connected',
            'dashboard.status.disconnected': 'Disconnected',
            'dashboard.status.connecting':   'Connecting…',

            // ---- logs ----
            'logs.subtitle':                 '— Logs',
            'logs.file':                     'File',
            'logs.lines':                    'Lines',
            'logs.follow':                   'Follow',
            'logs.scrollBottom':             '↓ Bottom',
            'logs.toast.loading':            'Loading…',
            'logs.toast.loaded':             '✓ {count} lines loaded',

            // ============================================================
            //  Extra keys for /users /states /logs /dashboard
            // ============================================================

            'users.detail.title':            'User detail',
            'users.create.title':            'Create user',
            'users.create.submit':           'Create',
            'users.group.title':             'Group',
            'users.tab.view':                'View',
            'users.tab.editMeta':            'Edit metadata',
            'users.tab.groupsTab':           'Groups',
            'users.tab.authTab':             'Auth',
            'users.tab.nfcTab':              'NFC',
            'users.refresh.auto30':          'Auto-refresh every 30s (paused while a modal is open)',
            'users.refresh.now':             'Refresh now',
            'users.refresh.btn':             '↻ Refresh',
            'users.table.name':              'Name',
            'users.table.admin':             'Admin?',
            'users.table.state':             'State',
            'users.table.validity':          'Validity',
            'users.table.actions':           'Actions',
            'users.table.description':       'Description',
            'users.table.members':           'Members',
            'users.section.loginPassword':   'Login password',
            'users.section.visuPassword':    'Visualization password',
            'users.section.accessCode':      'Access code',
            'users.section.assignedTags':    'Assigned NFC tags',
            'users.section.addTag':          'Add an NFC tag',
            'users.section.groups':          'Groups',
            'users.label.newPassword':       'New password',
            'users.label.newVisu':           'New visu password',
            'users.label.newAccessCode':     'New access code',
            'users.label.tagId':             'Tag ID',
            'users.label.tagName':           'Friendly name',
            'users.btn.savePassword':        'Save password',
            'users.btn.saveVisu':            'Save visu password',
            'users.btn.saveCode':            'Save access code',
            'users.btn.discover':            'Discover (tap a tag)',
            'users.btn.addTag':              'Add tag',
            'users.btn.disable':             'Disable',
            'users.btn.delete':              'Delete',
            'users.btn.viewEdit':            'View / Edit',
            'users.btn.editGroup':           'Edit',
            'users.btn.deleteGroup':         'Delete',
            'users.btn.removeFromGroup':     'Remove from group',
            'users.btn.removeTag':           'Remove tag',
            'users.btn.saveChanges':         'Save changes',
            'users.btn.saveMetadata':        'Save',
            'users.nfc.hint':                'Click <em>Discover</em>, then present the NFC card to any authorized reader.',
            'users.nfc.placeholder':         'AA:BB:CC:DD or AABBCCDD',
            'users.option.yes':              'Yes',
            'users.option.no':               'No',
            'users.badge.admin':             'ADMIN',
            'users.error.label':             'Error',

            'users.stateLabel.0':            'Active (no time limit)',
            'users.stateLabel.1':            'Disabled',
            'users.stateLabel.2':            'Enabled until',
            'users.stateLabel.3':            'Enabled from',
            'users.stateLabel.4':            'Timespan',
            'users.stateLabel.fallback':     'state {state}',
            'users.expirationAction.0':      'Deactivate',
            'users.expirationAction.1':      'Delete',
            'users.noMembers':               'no members',
            'users.noGroups':                'no groups assigned',

            'users.validity.noLimit':        'no limit',
            'users.validity.loading':        'loading…',
            'users.validity.until':          'until {date}',
            'users.validity.from':           'from {date}',
            'users.validity.range':          '{from} → {to}',
            'users.status.activeNoLimit':    'Active — no time limit',
            'users.status.disabled':         'Disabled',
            'users.status.activeExpires':    'Active — expires {date}',
            'users.status.expiredSince':     'Expired since {date}',
            'users.status.activeSince':      'Active since {date}',
            'users.status.pendingStarts':    'Pending — starts {date}',
            'users.status.pendingStartsUntil':'Pending — starts {from} (until {to})',
            'users.status.unknownState':     'Unknown userState = {state}',
            'users.status.missingValidUntil2':'Missing validUntil for state=2',
            'users.status.missingValidFrom3':'Missing validFrom for state=3',
            'users.status.missingForState4': 'Missing validFrom or validUntil for state=4',
            'users.snapshot':                '✓ {users} users, {groups} groups',
            'users.snapshotLoading':         'Loading…',

            'states.filter.title':           'Filters',
            'states.events.title':           'Events',
            'states.filter.hideUnknown':     'Hide',
            'states.filter.clearBtn':        'Clear',
            'states.filter.clearTitle':      'Clears the table (incoming events keep arriving)',
            'states.filter.live':            'Live',
            'states.filter.liveTitle':       'Checked: renders events live. Unchecked: pauses the display (incoming events are dropped, not buffered — no catch-up on resume).',
            'states.filter.searchPlaceholder':'Search (name, room, cat, UUID)',
            'states.table.time':             'Time',
            'states.table.type':             'Type',
            'states.table.room':             'Room',
            'states.table.cat':              'Category',
            'states.table.name':             'Name',
            'states.table.value':            'Value',
            'states.table.sparkline':        'Trend',
            'states.table.sparklineTitle':   'Trend of the last data points for this UUID (~30 latest events)',
            'states.counter.text':           '{visible} visible / {totalInTable} shown / {totalReceived} received',
            'states.filter.allMasc':         '(all)',
            'states.filter.allFem':          '(all)',
            'states.filter.kind.value':      'value',
            'states.filter.kind.text':       'text',

            'logs.section.files':            'Available log files',
            'logs.section.viewer':           'Viewer',
            'logs.label.minLevel':           'Min level',
            'logs.label.minLevelTitle':      'Shows severity ≥ selected (e.g. WARN → WARN + ERROR). Stack trace continuation lines inherit parent entry level.',
            'logs.label.lines':              'Lines',
            'logs.label.file':               'File',
            'logs.label.follow':             'Follow tail',
            'logs.btn.refresh':              'Refresh',

            'dashboard.panel.miniserver':    'Miniserver',
            'dashboard.panel.mqtt':          'MQTT',
            'dashboard.panel.session':       'Session',
            'dashboard.panel.connection':    'Connection',
            'dashboard.panel.identity':      'Identity',
            'dashboard.panel.token':         'Token',
            'dashboard.panel.bootstrap':     'Bootstrap',
            'dashboard.panel.keepalive':     'KeepAlive',
            'dashboard.panel.actions':       'Actions',
            'dashboard.panel.metrics':       'Metrics',
            'dashboard.panel.transport':     'Transport',
            'dashboard.dt.host':             'Host',
            'dashboard.dt.port':             'Port',
            'dashboard.dt.uuid':             'UUID',
            'dashboard.dt.permission':       'Permission',
            'dashboard.dt.preferred':        'Preferred',
            'dashboard.dt.effective':        'Effective',
            'dashboard.dt.webSocket':        'WebSocket',
            'dashboard.dt.downgrade':        'Downgrade',
            'dashboard.dt.session':          'Session',
            'dashboard.dt.serial':           'Serial',
            'dashboard.dt.version':          'Version',
            'dashboard.dt.generation':       'Generation',
            'dashboard.dt.https':            'HTTPS',
            'dashboard.dt.address':          'Address',
            'dashboard.dt.local':            'Local',
            'dashboard.dt.sdcard':           'SD card',
            'dashboard.sdcard.ok':           'OK',
            'dashboard.sdcard.error':        'ERROR',
            'dashboard.sdcard.pending':      'PENDING',
            'dashboard.dt.status':           'Status',
            'dashboard.dt.expiresAt':        'Expires at',
            'dashboard.dt.nextRefresh':      'Next refresh',
            'dashboard.fw.upToDate':         'up to date',
            'dashboard.fw.updateAvailable':  'update available',
            'dashboard.dt.rights':           'Rights',
            'dashboard.dt.keepalive':        'KeepAlive',
            'dashboard.dt.lastHandshake':    'Last handshake',
            'dashboard.dt.metrics':          'Metrics',
            'dashboard.dt.started':          'Started',
            'dashboard.dt.completed':        'Completed',
            'dashboard.dt.duration':         'Duration',
            'dashboard.dt.lastError':        'Last error',
            'dashboard.dt.protocol':         'Protocol',
            'dashboard.btn.connect':         'Connect to Miniserver',
            'dashboard.btn.disconnect':      'Disconnect from Miniserver',
            'dashboard.btn.reconnect':       'Force reconnect',
            'dashboard.btn.refreshToken':    'Refresh token',
            'dashboard.btn.killToken':       'Kill token',
            'dashboard.btn.reboot':          'Reboot Miniserver',
            'dashboard.btn.rebootConfirm':   'Reboot the Miniserver? All connections (WebSocket, live states) will drop for ~30–60 s while it restarts.',
            'dashboard.tag.noToken':         'NO TOKEN',
            'dashboard.tag.unresolved':      '— bootstrap (jdev/cfg/apiKey) hasn\'t landed yet.',
            'dashboard.tag.connectFirst':    '— connect first to acquire one.',
            'dashboard.tag.armed':           'ARMED — sending every',
            'dashboard.tag.idle':            'IDLE — not scheduled (no RUNNING session yet)',
            'dashboard.dt.path':             'Path',
            'dashboard.dt.clientId':         'Client ID',
            'dashboard.dt.mode':             'Mode',
            'dashboard.dt.qos':              'QoS',
            'dashboard.dt.lastRtt':          'Last RTT',
            'dashboard.meta.noResponse':     'no response yet',
            'dashboard.meta.noHandshake':    'never reached RUNNING',
            'dashboard.btn.connectMqtt':     'Connect to MQTT broker',
            'dashboard.btn.disconnectMqtt':  'Disconnect from MQTT broker'
        },

        de: {
            // ---- meta ----
            'app.name':              'LoxMQ',
            'lang.label':            'Sprache',
            'lang.fr':               'Français',
            'lang.en':               'English',
            'lang.de':               'Deutsch',

            // ---- navigation ----
            'nav.dashboard':         'Übersicht',
            'nav.states':            'Live-Status',
            'nav.schedules':         'Zeitpläne',
            'nav.users':             'Benutzer',
            'nav.logs':              'Protokolle',

            // ---- common ----
            'common.refresh':        'Aktualisieren',
            'common.refreshNow':     'Jetzt aktualisieren',
            'common.refreshAuto30':  'Automatische Aktualisierung alle 30s',
            'common.auto30s':        'auto 30s',
            'common.add':            'Hinzufügen',
            'common.edit':           'Bearbeiten',
            'common.delete':         'Löschen',
            'common.cancel':         'Abbrechen',
            'common.save':           'Speichern',
            'common.saveChanges':    'Speichern',
            'common.close':          'Schließen',
            'common.loading':        'Laden…',
            'common.search':         'Suchen',
            'common.filter':         'Filter',
            'common.actions':        'Aktionen',
            'common.name':           'Name',
            'common.status':         'Status',
            'common.unknown':        'Unbekannt',
            'common.yes':            'Ja',
            'common.no':             'Nein',
            'common.view':           'Ansicht',
            'common.backToDashboard':'← Übersicht',

            'footer.health':         '/q/health',

            // ---- schedules ----
            'schedules.subtitle':            '— Zeitpläne',
            'schedules.pageTitle':           'loxmq — Zeitpläne',
            'users.pageTitle':               'loxmq — Benutzer',
            'states.pageTitle':              'loxmq — Live-Status',
            'dashboard.pageTitle':           'loxmq — Übersicht',
            'logs.pageTitle':                'loxmq — Protokolle',
            'schedules.add.title':           'Zeitplan-Eintrag hinzufügen',
            'schedules.edit.title':          'Zeitplan-Eintrag bearbeiten',
            'schedules.entries.title':       'Einträge',
            'schedules.field.name':          'Name',
            'schedules.field.opMode':        'Betriebsmodus',
            'schedules.field.calMode':       'Kalendermodus',
            'schedules.placeholder.name':    'z. B. Sommerurlaub',

            'schedules.calMode.0':           '0 — Jährliches Datum',
            'schedules.calMode.1':           '1 — Oster-Versatz',
            'schedules.calMode.2':           '2 — Spezifisches Datum',
            'schedules.calMode.3':           '3 — Spezifischer Zeitraum',
            'schedules.calMode.4':           '4 — Jährlicher Zeitraum',
            'schedules.calMode.5':           '5 — Wochentag',
            'schedules.calMode.label.0':     'Jährliches Datum',
            'schedules.calMode.label.1':     'Oster-Versatz',
            'schedules.calMode.label.2':     'Spezifisches Datum',
            'schedules.calMode.label.3':     'Spezifischer Zeitraum',
            'schedules.calMode.label.4':     'Jährlicher Zeitraum',
            'schedules.calMode.label.5':     'Wochentag',

            'schedules.month.1':  'Jan',  'schedules.month.2':  'Feb',
            'schedules.month.3':  'Mär',  'schedules.month.4':  'Apr',
            'schedules.month.5':  'Mai',  'schedules.month.6':  'Jun',
            'schedules.month.7':  'Jul',  'schedules.month.8':  'Aug',
            'schedules.month.9':  'Sep',  'schedules.month.10': 'Okt',
            'schedules.month.11': 'Nov',  'schedules.month.12': 'Dez',

            'schedules.weekday.0': 'Mo', 'schedules.weekday.1': 'Di',
            'schedules.weekday.2': 'Mi', 'schedules.weekday.3': 'Do',
            'schedules.weekday.4': 'Fr', 'schedules.weekday.5': 'Sa',
            'schedules.weekday.6': 'So',

            'schedules.occurrence.0': 'jede', 'schedules.occurrence.1': '1.',
            'schedules.occurrence.2': '2.',   'schedules.occurrence.3': '3.',
            'schedules.occurrence.4': '4.',   'schedules.occurrence.5': 'letzte',

            'schedules.calMode5.of':    'von',
            'schedules.calMode5.month': 'Monat',

            'schedules.easter.label':   'Ostern',
            'schedules.easter.dayUnit': 'T',

            'schedules.builder.day':         'Tag',
            'schedules.builder.month':       'Monat',
            'schedules.builder.date':        'Datum',
            'schedules.builder.start':       'Start',
            'schedules.builder.end':         'Ende',
            'schedules.builder.startDay':    'Start-Tag',
            'schedules.builder.startMonth':  'Start-Monat',
            'schedules.builder.endDay':      'End-Tag',
            'schedules.builder.endMonth':    'End-Monat',
            'schedules.builder.weekday':     'Wochentag',
            'schedules.builder.occurrence':  'Vorkommen',
            'schedules.builder.easterOffset':'Versatz (Tage)',
            'schedules.builder.easterHint':  'z. B. -2 = Karfreitag, +1 = Ostermontag',
            'schedules.builder.everyMonth':  'Jeden Monat',
            'schedules.builder.rawPlaceholder':'Rohe Attribute mit / getrennt',

            'schedules.preview':             'An Miniserver gesendet →',
            'schedules.table.name':          'Name',
            'schedules.table.opMode':        'Betriebsm.',
            'schedules.table.calMode':       'Kalenderm.',
            'schedules.table.attrs':         'Attrs',
            'schedules.table.currently':     'Aktuell',

            'schedules.status.activeToday':       'Heute aktiv',
            'schedules.status.activeTodayOneShot':'Heute aktiv (einmalig)',
            'schedules.status.activeTodayEaster': 'Heute aktiv (Ostern {offset}T)',
            'schedules.status.activeUntil':       'Aktiv bis {date}',
            'schedules.status.activeEvery':       'Aktiv (jeden {day})',
            'schedules.status.activeNth':         'Aktiv ({occ} {day})',
            'schedules.status.next':              'Nächstes: {date}',
            'schedules.status.nextDay':           'Nächstes: {day}',
            'schedules.status.starts':            'Beginnt am {date}',
            'schedules.status.was':               'War am {date}',
            'schedules.status.ended':             'Beendet am {date}',
            'schedules.status.notThis':           'Nicht dieser {occ} {day}',
            'schedules.status.activeInMonth':     'Nur aktiv in Monat {month}',
            'schedules.status.missingMonthDay':   'Monat/Tag fehlt',
            'schedules.status.missingDate':       'Datum fehlt',
            'schedules.status.missingDates':      'Daten fehlen',
            'schedules.status.missingMonthsDays': 'Monate/Tage fehlen',
            'schedules.status.missingEaster':     'Oster-Versatz fehlt',
            'schedules.status.missingWeekday':    'Wochentag-Konfiguration fehlt',
            'schedules.status.unknownCalMode':    'Unbekannter calMode {mode}',

            'schedules.toast.loading':       'Laden…',
            'schedules.toast.loaded':        '✓ {count} Einträge geladen',
            'schedules.toast.creating':      'Erstelle "{name}"…',
            'schedules.toast.created':       '✓ "{name}" erstellt',
            'schedules.toast.deleting':      'Lösche "{name}"…',
            'schedules.toast.deleted':       '✓ "{name}" gelöscht',
            'schedules.toast.saving':        'Speichere "{name}"…',
            'schedules.toast.saved':         '✓ Gespeichert',
            'schedules.toast.updated':       '✓ "{name}" aktualisiert',
            'schedules.toast.editing':       'Bearbeite "{name}" ({uuid}…)',
            'schedules.toast.notInCache':    '⚠ Eintrag nicht im Cache: {uuid}',
            'schedules.toast.missingUuid':   '⚠ uuid fehlt',
            'schedules.toast.missingName':   '⚠ Name erforderlich',
            'schedules.toast.loxApp3':       'LoxAPP3 noch nicht geladen — numerische ID eingeben',
            'schedules.confirm.delete':      'Zeitplan "{name}" löschen?',

            // ---- users ----
            'users.subtitle':                '— Benutzer',
            'users.tab.users':               'Benutzer',
            'users.tab.groups':              'Gruppen',
            'users.add.user':                'Benutzer hinzufügen',
            'users.add.group':               'Gruppe hinzufügen',
            'users.edit.user':               'Benutzer bearbeiten',
            'users.edit.group':              'Gruppe bearbeiten',
            'users.field.name':              'Name',
            'users.field.fullName':          'Vollständiger Name',
            'users.field.password':          'Passwort',
            'users.field.visuPassword':      'Visualisierungs-Passwort',
            'users.field.accessCode':        'Zugangscode',
            'users.field.email':             'E-Mail',
            'users.field.userState':         'Zustand',
            'users.field.validFrom':         'Gültig ab',
            'users.field.validUntil':        'Gültig bis',
            'users.field.expirationAction':  'Aktion bei Ablauf',
            'users.field.groups':            'Gruppen',
            'users.field.members':           'Mitglieder',
            'users.field.permissions':       'Berechtigungen',
            'users.field.description':       'Beschreibung',
            'users.field.validity':          'Gültigkeit',
            'users.state.enabled':           'Aktiv',
            'users.state.disabled':          'Deaktiviert',
            'users.state.pending':           'Wartend',
            'users.state.expired':           'Abgelaufen',
            'users.action.disable':          'Deaktivieren',
            'users.action.enable':           'Aktivieren',
            'users.action.assignGroup':     'Zu Gruppe hinzufügen',
            'users.action.removeFromGroup': 'Aus Gruppe entfernen',
            'users.action.nfc':              'NFC',
            'users.action.changePassword':   'Passwort ändern',
            'users.tab.view':                'Ansicht',
            'users.tab.edit':                'Bearbeiten',
            'users.tab.auth':                'Authentifizierung',
            'users.tab.nfc':                 'NFC',
            'users.toast.loading':           'Laden…',
            'users.toast.loaded':            '✓ {count} Benutzer geladen',
            'users.confirm.delete':          'Benutzer "{name}" löschen?',
            'users.confirm.deleteGroup':     'Gruppe "{name}" löschen?',

            // ---- states ----
            'states.subtitle':               '— Live-Status',
            'states.title':                  'Status',
            'states.filter.search':          'Suchen (Control-Name, Raum, Kat., UUID)',
            'states.filter.unknown':         'UNKNOWN-Controls ausblenden',
            'states.col.control':            'Control',
            'states.col.room':               'Raum',
            'states.col.cat':                'Kategorie',
            'states.col.state':              'Status',
            'states.col.value':              'Wert',
            'states.col.updated':            'Aktualisiert',
            'states.col.sparkline':          'Verlauf',
            'states.toast.loading':          'Laden…',
            'states.toast.loaded':           '✓ {count} Status geladen',

            // ---- dashboard ----
            'dashboard.subtitle':            '— Übersicht',
            'dashboard.tile.miniserver':     'Miniserver',
            'dashboard.tile.mqtt':           'MQTT',
            'dashboard.tile.session':        'Sitzung',
            'dashboard.tile.uptime':         'Laufzeit',
            'dashboard.tile.version':        'Version',
            'dashboard.tile.heap':           'Heap',
            'dashboard.tile.threads':        'Threads',
            'dashboard.tile.states':         'Status',
            'dashboard.tile.commands':       'Befehle',
            'dashboard.status.connected':    'Verbunden',
            'dashboard.status.disconnected': 'Getrennt',
            'dashboard.status.connecting':   'Verbinde…',

            // ---- logs ----
            'logs.subtitle':                 '— Protokolle',
            'logs.file':                     'Datei',
            'logs.lines':                    'Zeilen',
            'logs.follow':                   'Verfolgen',
            'logs.scrollBottom':             '↓ Unten',
            'logs.toast.loading':            'Laden…',
            'logs.toast.loaded':             '✓ {count} Zeilen geladen',

            // ============================================================
            //  Zusätzliche Schlüssel für /users /states /logs /dashboard
            // ============================================================

            'users.detail.title':            'Benutzer-Details',
            'users.create.title':            'Benutzer erstellen',
            'users.create.submit':           'Erstellen',
            'users.group.title':             'Gruppe',
            'users.tab.view':                'Ansicht',
            'users.tab.editMeta':            'Metadaten bearbeiten',
            'users.tab.groupsTab':           'Gruppen',
            'users.tab.authTab':             'Auth',
            'users.tab.nfcTab':              'NFC',
            'users.refresh.auto30':          'Automatische Aktualisierung alle 30s (pausiert bei offenem Dialog)',
            'users.refresh.now':             'Jetzt aktualisieren',
            'users.refresh.btn':             '↻ Aktualisieren',
            'users.table.name':              'Name',
            'users.table.admin':             'Admin?',
            'users.table.state':             'Zustand',
            'users.table.validity':          'Gültigkeit',
            'users.table.actions':           'Aktionen',
            'users.table.description':       'Beschreibung',
            'users.table.members':           'Mitglieder',
            'users.section.loginPassword':   'Anmelde-Passwort',
            'users.section.visuPassword':    'Visualisierungs-Passwort',
            'users.section.accessCode':      'Zugangscode',
            'users.section.assignedTags':    'Zugewiesene NFC-Tags',
            'users.section.addTag':          'NFC-Tag hinzufügen',
            'users.section.groups':          'Gruppen',
            'users.label.newPassword':       'Neues Passwort',
            'users.label.newVisu':           'Neues Visu-Passwort',
            'users.label.newAccessCode':     'Neuer Zugangscode',
            'users.label.tagId':             'Tag-ID',
            'users.label.tagName':           'Anzeigename',
            'users.btn.savePassword':        'Passwort speichern',
            'users.btn.saveVisu':            'Visu-Passwort speichern',
            'users.btn.saveCode':            'Zugangscode speichern',
            'users.btn.discover':            'Erkennen (Tag berühren)',
            'users.btn.addTag':              'Tag hinzufügen',
            'users.btn.disable':             'Deaktivieren',
            'users.btn.delete':              'Löschen',
            'users.btn.viewEdit':            'Ansehen / Bearbeiten',
            'users.btn.editGroup':           'Bearbeiten',
            'users.btn.deleteGroup':         'Löschen',
            'users.btn.removeFromGroup':     'Aus Gruppe entfernen',
            'users.btn.removeTag':           'Tag entfernen',
            'users.btn.saveChanges':         'Änderungen speichern',
            'users.btn.saveMetadata':        'Speichern',
            'users.nfc.hint':                'Klicken Sie auf <em>Erkennen</em> und halten Sie dann die NFC-Karte an einen autorisierten Leser.',
            'users.nfc.placeholder':         'AA:BB:CC:DD oder AABBCCDD',
            'users.option.yes':              'Ja',
            'users.option.no':               'Nein',
            'users.badge.admin':             'ADMIN',
            'users.error.label':             'Fehler',

            'users.stateLabel.0':            'Aktiv (unbegrenzt)',
            'users.stateLabel.1':            'Deaktiviert',
            'users.stateLabel.2':            'Aktiv bis',
            'users.stateLabel.3':            'Aktiv ab',
            'users.stateLabel.4':            'Zeitraum',
            'users.stateLabel.fallback':     'Status {state}',
            'users.expirationAction.0':      'Deaktivieren',
            'users.expirationAction.1':      'Löschen',
            'users.noMembers':               'keine Mitglieder',
            'users.noGroups':                'keine Gruppen zugewiesen',

            'users.validity.noLimit':        'unbegrenzt',
            'users.validity.loading':        'Laden…',
            'users.validity.until':          'bis {date}',
            'users.validity.from':           'ab {date}',
            'users.validity.range':          '{from} → {to}',
            'users.status.activeNoLimit':    'Aktiv — ohne Zeitbegrenzung',
            'users.status.disabled':         'Deaktiviert',
            'users.status.activeExpires':    'Aktiv — läuft am {date} ab',
            'users.status.expiredSince':     'Abgelaufen seit {date}',
            'users.status.activeSince':      'Aktiv seit {date}',
            'users.status.pendingStarts':    'Wartend — startet am {date}',
            'users.status.pendingStartsUntil':'Wartend — startet am {from} (bis {to})',
            'users.status.unknownState':     'Unbekannter userState = {state}',
            'users.status.missingValidUntil2':'validUntil fehlt für state=2',
            'users.status.missingValidFrom3':'validFrom fehlt für state=3',
            'users.status.missingForState4': 'validFrom oder validUntil fehlt für state=4',
            'users.snapshot':                '✓ {users} Benutzer, {groups} Gruppen',
            'users.snapshotLoading':         'Laden…',

            'states.filter.title':           'Filter',
            'states.events.title':           'Ereignisse',
            'states.filter.hideUnknown':     'Ausblenden',
            'states.filter.clearBtn':        'Leeren',
            'states.filter.clearTitle':      'Leert die Tabelle (eingehende Ereignisse kommen weiter an)',
            'states.filter.live':            'Live',
            'states.filter.liveTitle':       'Aktiviert: zeigt Ereignisse live an. Nicht aktiviert: pausiert die Anzeige (eingehende Ereignisse werden verworfen, nicht gepuffert — kein Nachholen bei Fortsetzung).',
            'states.filter.searchPlaceholder':'Suchen (Name, Raum, Kat., UUID)',
            'states.table.time':             'Zeit',
            'states.table.type':             'Typ',
            'states.table.room':             'Raum',
            'states.table.cat':              'Kategorie',
            'states.table.name':             'Name',
            'states.table.value':            'Wert',
            'states.table.sparkline':        'Verlauf',
            'states.table.sparklineTitle':   'Verlauf der letzten Datenpunkte für diese UUID (~30 letzte Ereignisse)',
            'states.counter.text':           '{visible} sichtbar / {totalInTable} angezeigt / {totalReceived} empfangen',
            'states.filter.allMasc':         '(alle)',
            'states.filter.allFem':          '(alle)',
            'states.filter.kind.value':      'Wert',
            'states.filter.kind.text':       'Text',

            'logs.section.files':            'Verfügbare Protokolldateien',
            'logs.section.viewer':           'Anzeige',
            'logs.label.minLevel':           'Min. Stufe',
            'logs.label.minLevelTitle':      'Zeigt Schweregrad ≥ ausgewählt (z. B. WARN → WARN + ERROR). Stack-Trace-Fortsetzungen erben die Stufe des Eltern-Eintrags.',
            'logs.label.lines':              'Zeilen',
            'logs.label.file':               'Datei',
            'logs.label.follow':             'Ende verfolgen',
            'logs.btn.refresh':              'Aktualisieren',

            'dashboard.panel.miniserver':    'Miniserver',
            'dashboard.panel.mqtt':          'MQTT',
            'dashboard.panel.session':       'Sitzung',
            'dashboard.panel.connection':    'Verbindung',
            'dashboard.panel.identity':      'Identität',
            'dashboard.panel.token':         'Token',
            'dashboard.panel.bootstrap':     'Bootstrap',
            'dashboard.panel.keepalive':     'KeepAlive',
            'dashboard.panel.actions':       'Aktionen',
            'dashboard.panel.metrics':       'Metriken',
            'dashboard.panel.transport':     'Transport',
            'dashboard.dt.host':             'Host',
            'dashboard.dt.port':             'Port',
            'dashboard.dt.uuid':             'UUID',
            'dashboard.dt.permission':       'Berechtigung',
            'dashboard.dt.preferred':        'Bevorzugt',
            'dashboard.dt.effective':        'Effektiv',
            'dashboard.dt.webSocket':        'WebSocket',
            'dashboard.dt.downgrade':        'Herabstufung',
            'dashboard.dt.session':          'Sitzung',
            'dashboard.dt.serial':           'Seriennummer',
            'dashboard.dt.version':          'Version',
            'dashboard.dt.generation':       'Generation',
            'dashboard.dt.https':            'HTTPS',
            'dashboard.dt.address':          'Adresse',
            'dashboard.dt.local':            'Lokal',
            'dashboard.dt.sdcard':           'SD-Karte',
            'dashboard.sdcard.ok':           'OK',
            'dashboard.sdcard.error':        'FEHLER',
            'dashboard.sdcard.pending':      'AUSSTEHEND',
            'dashboard.dt.status':           'Status',
            'dashboard.dt.expiresAt':        'Läuft ab am',
            'dashboard.dt.nextRefresh':      'Nächste Aktualisierung',
            'dashboard.fw.upToDate':         'aktuell',
            'dashboard.fw.updateAvailable':  'Update verfügbar',
            'dashboard.dt.rights':           'Rechte',
            'dashboard.dt.keepalive':        'KeepAlive',
            'dashboard.dt.lastHandshake':    'Letzter Handshake',
            'dashboard.dt.metrics':          'Metriken',
            'dashboard.dt.started':          'Gestartet',
            'dashboard.dt.completed':        'Abgeschlossen',
            'dashboard.dt.duration':         'Dauer',
            'dashboard.dt.lastError':        'Letzter Fehler',
            'dashboard.dt.protocol':         'Protokoll',
            'dashboard.btn.connect':         'Mit Miniserver verbinden',
            'dashboard.btn.disconnect':      'Von Miniserver trennen',
            'dashboard.btn.reconnect':       'Neuverbindung erzwingen',
            'dashboard.btn.refreshToken':    'Token erneuern',
            'dashboard.btn.killToken':       'Token löschen',
            'dashboard.btn.reboot':          'Miniserver neu starten',
            'dashboard.btn.rebootConfirm':   'Miniserver neu starten? Alle Verbindungen (WebSocket, Live-Status) werden für ~30–60 s getrennt, während er neu startet.',
            'dashboard.tag.noToken':         'KEIN TOKEN',
            'dashboard.tag.unresolved':      '— Bootstrap (jdev/cfg/apiKey) noch nicht angekommen.',
            'dashboard.tag.connectFirst':    '— zuerst verbinden, um ein Token zu erhalten.',
            'dashboard.tag.armed':           'AKTIV — sendet alle',
            'dashboard.tag.idle':            'INAKTIV — nicht geplant (noch keine RUNNING-Sitzung)',
            'dashboard.dt.path':             'Pfad',
            'dashboard.dt.clientId':         'Client-ID',
            'dashboard.dt.mode':             'Modus',
            'dashboard.dt.qos':              'QoS',
            'dashboard.dt.lastRtt':          'Letzte RTT',
            'dashboard.meta.noResponse':     'noch keine Antwort',
            'dashboard.meta.noHandshake':    'nie RUNNING erreicht',
            'dashboard.btn.connectMqtt':     'Mit MQTT-Broker verbinden',
            'dashboard.btn.disconnectMqtt':  'Von MQTT-Broker trennen'
        }
    };

    // ============================================================
    //  ETAT + DETECTION
    // ============================================================
    var currentLang = null;

    function detectInitialLang() {
        try {
            var stored = window.localStorage.getItem('lang');
            if (stored && SUPPORTED.indexOf(stored) !== -1) return stored;
        } catch (e) { /* localStorage unavailable */ }

        var nav = (window.navigator.language || window.navigator.userLanguage || '')
                    .toLowerCase().split('-')[0];
        if (SUPPORTED.indexOf(nav) !== -1) return nav;

        return DEFAULT_LANG;
    }

    // ============================================================
    //  API
    // ============================================================
    function t(key, params) {
        var dict = I18N[currentLang] || I18N[DEFAULT_LANG] || {};
        var val  = dict[key];
        if (val == null) {
            // fallback chain : default lang puis clé brute (visibilité)
            val = (I18N[DEFAULT_LANG] || {})[key];
            if (val == null) val = key;
        }
        if (params) {
            val = val.replace(/\{(\w+)\}/g, function (_, name) {
                return params[name] != null ? params[name] : ('{' + name + '}');
            });
        }
        return val;
    }

    function applyI18n(root) {
        root = root || document;

        // textContent via data-i18n
        var els = root.querySelectorAll('[data-i18n]');
        for (var i = 0; i < els.length; i++) {
            els[i].textContent = t(els[i].getAttribute('data-i18n'));
        }

        // innerHTML via data-i18n-html (rare, nécessite contenu safe)
        els = root.querySelectorAll('[data-i18n-html]');
        for (i = 0; i < els.length; i++) {
            els[i].innerHTML = t(els[i].getAttribute('data-i18n-html'));
        }

        // attributs via data-i18n-attr="attr1:key1,attr2:key2"
        els = root.querySelectorAll('[data-i18n-attr]');
        for (i = 0; i < els.length; i++) {
            var spec = els[i].getAttribute('data-i18n-attr');
            spec.split(',').forEach(function (pair) {
                var p = pair.split(':');
                if (p.length === 2) {
                    els[i].setAttribute(p[0].trim(), t(p[1].trim()));
                }
            });
        }

        // page title via <html data-i18n-page-title="key">
        var pageKey = document.documentElement.getAttribute('data-i18n-page-title');
        if (pageKey) document.title = t(pageKey);

        document.documentElement.setAttribute('lang', currentLang);
    }

    function setLang(lang) {
        if (SUPPORTED.indexOf(lang) === -1) lang = DEFAULT_LANG;
        currentLang = lang;
        try { window.localStorage.setItem('lang', lang); } catch (e) {}
        applyI18n();
        // sync tous les switchers de la page
        var switchers = document.querySelectorAll('[data-i18n-switcher]');
        for (var i = 0; i < switchers.length; i++) {
            if (switchers[i].value !== lang) switchers[i].value = lang;
        }
        // notify per-page JS (re-render dynamic content)
        try {
            window.dispatchEvent(new CustomEvent('i18n:changed', {
                detail: { lang: lang }
            }));
        } catch (e) { /* CustomEvent unsupported ? */ }
    }

    function getLang() {
        return currentLang;
    }

    function wireSwitchers() {
        var switchers = document.querySelectorAll('[data-i18n-switcher]');
        for (var i = 0; i < switchers.length; i++) {
            var sel = switchers[i];
            sel.value = currentLang;
            sel.addEventListener('change', (function (s) {
                return function () { setLang(s.value); };
            })(sel));
        }
    }

    // ============================================================
    //  BOOT
    // ============================================================
    currentLang = detectInitialLang();

    // expose API
    window.I18N      = I18N;
    window.t         = t;
    window.applyI18n = applyI18n;
    window.setLang   = setLang;
    window.getLang   = getLang;

    function boot() {
        applyI18n();
        wireSwitchers();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', boot);
    } else {
        boot();
    }
})(window);
