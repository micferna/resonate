# Sécurité

## Signaler une faille

Ouvrez un [avis de sécurité privé](https://github.com/micferna/resonate/security/advisories/new)
plutôt qu'une issue publique. Une réponse est visée sous une semaine.

## Ce qui protège quoi

| Élément sensible | Protection |
|---|---|
| Mots de passe, clés privées SSH | AES-256-GCM, clé non exportable du KeyStore matériel |
| Identité des serveurs SSH | Empreinte de clé d'hôte mémorisée puis vérifiée (TOFU) |
| Trafic WebDAV / Subsonic | TLS validé par le magasin de certificats du système |
| Trafic SMB | Signature de session activée |
| Intégrité des mises à jour | SHA-256 publiée + signature d'application vérifiée par Android |
| Sauvegardes automatiques | Désactivées : elles ne transporteraient que des secrets illisibles |

Les URI internes ne portent qu'une référence de source et un chemin
(`resonate://<id>/<chemin>`). Aucun identifiant ne transite par les clés de cache,
l'index des téléchargements ou les journaux.

## Analyse statique

CodeQL tourne à chaque poussée et une fois par semaine. Le code généré (KSP, AGP)
est exclu : il n'est ni écrit ni modifiable ici.

Quatre alertes sont écartées volontairement. Les voici avec leur raison — elles
sont fermées dans l'onglet Security, ce document en tient le détail.

### MD5 dans l'authentification Subsonic

*`java/potentially-weak-cryptographic-algorithm` — SubsonicConnector*

MD5 est **imposé par le protocole Subsonic**, ce n'est pas un choix côté client :
le serveur calcule la même empreinte, en changer romprait la compatibilité avec
Navidrome, Airsonic, Gonic et les autres.

Des deux modes d'authentification que le protocole propose, celui retenu est le
moins mauvais : le jeton est salé aléatoirement à chaque requête et le mot de passe
ne circule jamais. L'alternative — le paramètre `p=` — transmet le mot de passe en
clair ou en simple hexadécimal.

L'ensemble voyage par ailleurs en HTTPS dès lors que la source est configurée en
TLS, ce qui est le réglage par défaut.

### Absence d'épinglage de certificat, GitHub

*`java/android/missing-certificate-pinning` — UpdateChecker*

GitHub fait tourner ses certificats et déconseille de les épingler. Une rotation
rendrait l'updater inopérant sur toutes les installations existantes, sans
possibilité de correctif — puisque c'est précisément l'updater qui serait cassé.

L'intégrité de la mise à jour repose sur deux mécanismes plus solides :

1. l'empreinte SHA-256 publiée à côté de l'APK est vérifiée avant installation ;
2. Android refuse tout APK qui ne serait pas signé par la même clé que
   l'application déjà installée.

Le second point est décisif : même un APK malveillant servi par un serveur usurpé
ne peut pas remplacer Resonate.

### Absence d'épinglage de certificat, WebDAV

*`java/android/missing-certificate-pinning` — WebDavConnector*

Impossible par construction : c'est l'utilisateur qui configure l'adresse de son
serveur — son Nextcloud, son NAS, sa machine. Aucun certificat n'est connu à
l'avance.

La validation s'appuie sur le magasin de certificats du système, y compris les
autorités que l'utilisateur y a lui-même ajoutées, ce qui couvre le cas d'un
certificat auto-signé installé volontairement.

### Intention implicite supposée

*`java/android/implicit-pendingintents` — UpdateWorker*

Faux positif. L'intention est explicite : le composant est nommé par
`Intent(context, MainActivity::class.java)`. Le `setAction()` qui suit ne l'efface
pas, ce qui explique vraisemblablement le déclenchement de la règle.

Le `PendingIntent` est en outre créé avec `FLAG_IMMUTABLE` : aucune application
tierce ne peut en modifier le contenu.

## Chaîne de construction

- Le wrapper Gradle est épinglé par empreinte SHA-256 et validé en CI.
- Dependabot surveille les dépendances Gradle **et** les actions GitHub, qui sont
  du code exécuté avec accès au dépôt.
- Les versions de BouncyCastle sont relevées explicitement au-dessus de celles
  qu'apportent sshj et SMBJ.
- La clé de signature ne quitte pas les secrets du dépôt ; l'APK publié est
  vérifié par `apksigner` avant que la Release ne soit créée.
