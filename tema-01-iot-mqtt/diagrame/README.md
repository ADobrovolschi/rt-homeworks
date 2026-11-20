# Diagrame Tema 01

Fiecare fișier `.mmd` conține sursa Mermaid, iar fișierul `.svg` cu același nume
este imaginea diagramei. SVG-urile se pot deschide direct în browser și se pot
exporta în PNG din draw.io sau dintr-un editor SVG.

Diagrama de timp este schematică, pe intervalul 0–10 s: taskul periodic are
perioada inițială de 2 s, taskul sporadic publică la 2 s și 8 s, iar alarma de
la 4 s este respinsă deoarece nu a trecut intervalul minim de 5 s. Este ilustrat
și un job aperiodic de procesare a unei comenzi.

Fiecare eveniment de alarmă păstrează împreună temperatura și pragul citite de
jobul periodic care l-a generat. Astfel, mesajul alarmei reflectă pragul activ
la momentul declanșării, chiar dacă ulterior operatorul schimbă pragul.
